import request, { type Test } from 'supertest';

import { createApp } from '../../src/app';
import { UpstreamError } from '../../src/common/errors';
import { routesClient } from '../../src/integrations/routes.client';
import { RecommendationModel } from '../../src/recommendation/models';
import { MeetupModel } from '../../src/users-groups/models';
import { fairCreditService } from '../../src/voting-fairness/fair-credit.service';
import { FairCreditEntryModel, FairCreditModel, SettlementModel } from '../../src/voting-fairness/models';
import { bearer } from '../helpers/auth';
import { clearDb, startDb, stopDb } from '../helpers/db';
import { addMember, createGroup, createSettleableMeetup, createUserN } from '../helpers/factories';

beforeAll(startDb);
afterEach(async () => {
  jest.restoreAllMocks();
  await clearDb();
});
afterAll(stopDb);

// A settleable meetup whose stored estimates are 30 minutes for the organizer and 10 for the member
async function setup() {
  const organizer = await createUserN(1);
  const member = await createUserN(2);
  const group = await createGroup(organizer.id);
  await addMember(group.id, member.id);
  const meetup = await createSettleableMeetup(group.id, organizer.id, [[organizer.id, 1800], [member.id, 600]]);
  return { organizer, member, group, meetup };
}

function settle(meetupId: string, userId: string): Test {
  return request(createApp()).post(`/api/meetups/${meetupId}/settle`).set('Authorization', bearer(userId));
}

// Silences the error handler's logging for tests that expect a 500
function quietConsole() {
  jest.spyOn(console, 'error').mockImplementation(() => undefined);
}

// Interface POST /api/meetups/:meetupId/settle
describe('Mocked: POST /api/meetups/:meetupId/settle', () => {
  // Mocked behavior: the update that moves the meetup to SETTLED rejects once
  // Input: organizer settles, then settles again
  // Expected status code: 500; then 200
  // Expected behavior: the credits applied by the failed request are kept and not applied again by the retry
  // Expected output: the second response is the meetup with status SETTLED
  test('retries after a failed final status update without applying credits again', async () => {
    quietConsole();
    const { organizer, group, meetup } = await setup();
    const update = jest.spyOn(MeetupModel, 'updateOne').mockRejectedValueOnce(new Error('db down') as never);

    expect((await settle(meetup.id, organizer.id)).status).toBe(500);
    expect(update).toHaveBeenCalledTimes(1);
    expect(await fairCreditService.getBalance(organizer.id, group.id)).toBe(600);

    const retried = await settle(meetup.id, organizer.id);

    expect(retried.status).toBe(200);
    expect(retried.body.status).toBe('SETTLED');
    expect(await fairCreditService.getBalance(organizer.id, group.id)).toBe(600);
    expect(await FairCreditEntryModel.countDocuments()).toBe(2);
  });

  // Mocked behavior: the balance update rejects once, after the ledger and the settlement snapshot were written
  // Input: organizer settles, then settles again
  // Expected status code: 500; then 200
  // Expected behavior: no balance moves in the failed request; the retry reuses the frozen snapshot and applies
  //   each change exactly once (+10 and -10 minutes)
  // Expected output: None
  test('resumes an interrupted settlement from its snapshot', async () => {
    quietConsole();
    const { organizer, member, group, meetup } = await setup();
    jest.spyOn(FairCreditModel, 'bulkWrite').mockRejectedValueOnce(new Error('db down') as never);

    expect((await settle(meetup.id, organizer.id)).status).toBe(500);
    expect(await SettlementModel.countDocuments()).toBe(1);
    expect(await fairCreditService.getBalance(organizer.id, group.id)).toBe(0);

    expect((await settle(meetup.id, organizer.id)).status).toBe(200);

    expect(await SettlementModel.countDocuments()).toBe(1);
    expect(await fairCreditService.getBalance(organizer.id, group.id)).toBe(600);
    expect(await fairCreditService.getBalance(member.id, group.id)).toBe(-600);
  });

  // Mocked behavior: the recommendation stored no travel estimates; Google Routes returns 40 and 20 minutes
  // Input: organizer settles
  // Expected status code: 200
  // Expected behavior: Routes is asked once for both participants; both ledger entries are ESTIMATED and the
  //   organizer gains 10 minutes of credit
  // Expected output: None
  test('fetches missing estimates from Google Routes', async () => {
    const { organizer, group, meetup } = await setup();
    await RecommendationModel.deleteMany({ meetupId: meetup.id });
    const estimate = jest.spyOn(routesClient, 'travelSeconds').mockResolvedValue([[2400], [1200]]);

    expect((await settle(meetup.id, organizer.id)).status).toBe(200);

    expect(estimate).toHaveBeenCalledTimes(1);
    expect(await fairCreditService.getBalance(organizer.id, group.id)).toBe(600);
    expect(await FairCreditEntryModel.countDocuments({ source: 'ESTIMATED' })).toBe(2);
  });

  // Mocked behavior: the recommendation stored no travel estimates; Google Routes finds no route for the organizer
  // Input: organizer settles
  // Expected status code: 409
  // Expected behavior: the meetup stays FINALIZED; no snapshot and no Fair Credit is written
  // Expected output: None
  test('leaves the meetup finalized when a participant has no usable estimate', async () => {
    const { organizer, meetup } = await setup();
    await RecommendationModel.deleteMany({ meetupId: meetup.id });
    jest.spyOn(routesClient, 'travelSeconds').mockResolvedValue([[null], [1200]]);

    expect((await settle(meetup.id, organizer.id)).status).toBe(409);

    expect((await MeetupModel.findById(meetup.id).orFail()).status).toBe('FINALIZED');
    expect(await FairCreditModel.countDocuments()).toBe(0);
    expect(await SettlementModel.countDocuments()).toBe(0);
  });

  // Mocked behavior: the recommendation stored no travel estimates; Google Routes fails
  // Input: organizer settles
  // Expected status code: 502
  // Expected behavior: the meetup stays FINALIZED and nothing is frozen, so settling can be tried again later
  // Expected output: { error: "Unable to estimate travel times. Please try again later." }
  test('reports a Google Routes failure without freezing the settlement', async () => {
    const { organizer, meetup } = await setup();
    await RecommendationModel.deleteMany({ meetupId: meetup.id });
    const message = 'Unable to estimate travel times. Please try again later.';
    jest.spyOn(routesClient, 'travelSeconds').mockRejectedValue(new UpstreamError(message));

    const result = await settle(meetup.id, organizer.id);

    expect(result.status).toBe(502);
    expect(result.body).toEqual({ error: message });
    expect((await MeetupModel.findById(meetup.id).orFail()).status).toBe('FINALIZED');
    expect(await SettlementModel.countDocuments()).toBe(0);
  });
});
