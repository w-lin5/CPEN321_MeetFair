import request from 'supertest';

import { createApp } from '../../src/app';
import { userService } from '../../src/users-groups/user.service';
import { fairCreditService } from '../../src/voting-fairness/fair-credit.service';
import { FairCreditEntryModel, FairCreditModel } from '../../src/voting-fairness/models';
import { bearer } from '../helpers/auth';
import { clearDb, startDb, stopDb } from '../helpers/db';
import { addMember, createGroup, createMeetup, createUserN } from '../helpers/factories';

beforeAll(startDb);
afterEach(async () => {
  jest.restoreAllMocks();
  await clearDb();
});
afterAll(stopDb);

// Interface GET /api/groups/:groupId/fairness
describe('Mocked: GET /api/groups/:groupId/fairness', () => {
  // Input: member of the group
  // Expected status code: 500
  // Expected output: { error: "Internal Server Error" }
  // Mocked behavior: loading the ledger throws
  test('Ledger query failure', async () => {
    jest.spyOn(console, 'error').mockImplementation(() => undefined);
    const user = await createUserN(1);
    const group = await createGroup(user.id);
    jest.spyOn(FairCreditEntryModel, 'find').mockImplementation(() => {
      throw new Error('db down');
    });

    const res = await request(createApp()).get(`/api/groups/${group.id}/fairness`).set('Authorization', bearer(user.id));

    expect(res.status).toBe(500);
    expect(res.body).toEqual({ error: 'Internal Server Error' });
  });

  // Input: member of a group with a second member
  // Expected status code: 200
  // Expected output: a member whose user record cannot be found is listed with name null
  // Mocked behavior: userService.findByIds resolves only the caller
  test('Member without a user record', async () => {
    const user = await createUserN(1);
    const grace = await createUserN(2);
    const group = await createGroup(user.id);
    await addMember(group.id, grace.id);
    jest.spyOn(userService, 'findByIds').mockResolvedValue([user] as never);

    const res = await request(createApp()).get(`/api/groups/${group.id}/fairness`).set('Authorization', bearer(user.id));

    expect(res.status).toBe(200);
    expect(res.body.members).toEqual([
      { userId: user.id, name: 'User 1', balance: 0 },
      { userId: grace.id, name: null, balance: 0 },
    ]);
  });
});

// Interface fairCreditService.settleMeetup
describe('Mocked: fairCreditService.settleMeetup', () => {
  async function setup() {
    const ada = await createUserN(1);
    const grace = await createUserN(2);
    const group = await createGroup(ada.id);
    await addMember(group.id, grace.id);
    const meetup = await createMeetup(group.id, ada.id);
    const trips = [ada.id, grace.id].map((userId, i) => ({
      userId,
      mode: 'WALK' as const,
      actualSeconds: (i + 1) * 600,
      source: 'TRACKED' as const,
    }));
    return { ada, grace, group, meetup, trips };
  }

  // Mocked behavior: FairCreditEntryModel.bulkWrite rejects
  // Input: valid settlement of two trips
  // Expected behavior: the error propagates; neither a ledger entry nor a balance is written
  // Expected output: None
  test('Ledger write failure leaves balances untouched', async () => {
    const { group, meetup, trips } = await setup();
    const write = jest.spyOn(FairCreditEntryModel, 'bulkWrite').mockRejectedValue(new Error('db down') as never);

    await expect(fairCreditService.settleMeetup(group.id, meetup.id, trips)).rejects.toThrow('db down');

    expect(write).toHaveBeenCalledTimes(1);
    expect(await FairCreditEntryModel.countDocuments()).toBe(0);
    expect(await FairCreditModel.countDocuments()).toBe(0);
  });

  // Mocked behavior: FairCreditModel.bulkWrite rejects once
  // Input: valid settlement of a 10 and a 20 minute trip, then the same settlement again without the failure
  // Expected behavior: the error propagates with the ledger already written; settling again completes it, and
  //   every balance changes exactly once (-5 and +5 minutes)
  // Expected output: None
  test('Balance write failure is repaired by settling again', async () => {
    const { ada, grace, group, meetup, trips } = await setup();
    jest.spyOn(FairCreditModel, 'bulkWrite').mockRejectedValueOnce(new Error('db down') as never);

    await expect(fairCreditService.settleMeetup(group.id, meetup.id, trips)).rejects.toThrow('db down');
    expect(await FairCreditEntryModel.countDocuments()).toBe(2);
    expect(await FairCreditModel.countDocuments()).toBe(0);

    await fairCreditService.settleMeetup(group.id, meetup.id, trips);
    await fairCreditService.settleMeetup(group.id, meetup.id, trips);

    expect(await FairCreditEntryModel.countDocuments()).toBe(2);
    expect(await fairCreditService.getBalance(ada.id, group.id)).toBe(-300);
    expect(await fairCreditService.getBalance(grace.id, group.id)).toBe(300);
  });
});
