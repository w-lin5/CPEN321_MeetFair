import request from 'supertest';

import { createApp } from '../../src/app';
import { userService } from '../../src/users-groups/user.service';
import { MeetupModel } from '../../src/users-groups/models';
import { fairCreditService } from '../../src/voting-fairness/fair-credit.service';
import { FairCreditEntryModel, FairCreditModel, SettlementModel } from '../../src/voting-fairness/models';
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
    return { group, meetup, trips };
  }

  test('deleting a settled meetup preserves ledger values and renders missing historical names as null', async () => {
    const { group, meetup, trips } = await setup();
    await fairCreditService.settleMeetup(group.id, meetup.id, trips);
    const before = await FairCreditEntryModel.find({ meetupId: meetup.id }).lean();
    await MeetupModel.deleteOne({ _id: meetup.id });
    const history = await fairCreditService.getGroupFairness(group.id, trips[0]!.userId);
    expect(history.ledger).toHaveLength(2);
    expect(history.ledger.every(entry => entry.meetupTitle === null && entry.placeName === null)).toBe(true);
    expect(await FairCreditEntryModel.find({ meetupId: meetup.id }).lean()).toEqual(before);
    expect(history.members.map(member => member.balance)).toEqual([-300, 300]);
  });

  test.each([null, 'connection lost', {}, { code: 123 }])('a resume ledger write failure propagates without applying balances: %p', async failure => {
    const { group, meetup, trips } = await setup();
    jest.spyOn(FairCreditEntryModel, 'updateOne').mockRejectedValueOnce(failure);
    await expect(fairCreditService.settleMeetup(group.id, meetup.id, trips, true)).rejects.toBe(failure);
    expect(await FairCreditEntryModel.countDocuments()).toBe(0);
    expect(await FairCreditModel.countDocuments()).toBe(0);
  });

  test.each(['ledger', 'balance'] as const)('a committed %s insert followed by a duplicate-key response is safe to resume', async stage => {
    const { group, meetup, trips } = await setup();
    const model = stage === 'ledger' ? FairCreditEntryModel : FairCreditModel;
    // Both model update methods share the updateOne boundary; retain each real write.
    if (stage === 'ledger') {
      const original = FairCreditEntryModel.updateOne.bind(FairCreditEntryModel);
      jest.spyOn(FairCreditEntryModel, 'updateOne').mockImplementationOnce((...args: Parameters<typeof original>) => {
        const query = original(...args);
        const execute = query.exec.bind(query);
        jest.spyOn(query, 'exec').mockImplementationOnce(async () => {
          await execute(); throw Object.assign(new Error('duplicate ledger'), { code: 11000 });
        });
        return query;
      });
    } else {
      const original = FairCreditModel.updateOne.bind(FairCreditModel);
      jest.spyOn(FairCreditModel, 'updateOne').mockImplementationOnce((...args: Parameters<typeof original>) => {
        const query = original(...args);
        const execute = query.exec.bind(query);
        jest.spyOn(query, 'exec').mockImplementationOnce(async () => {
          await execute(); throw Object.assign(new Error('duplicate balance'), { code: 11000 });
        });
        return query;
      });
    }
    await fairCreditService.settleMeetup(group.id, meetup.id, trips, true);
    expect(await model.countDocuments()).toBe(2);
    expect(await fairCreditService.getBalance(trips[0]!.userId, group.id)).toBe(-300);
    expect(await fairCreditService.getBalance(trips[1]!.userId, group.id)).toBe(300);
    await fairCreditService.settleMeetup(group.id, meetup.id, trips, true);
    expect(await FairCreditEntryModel.countDocuments()).toBe(2);
    expect(await fairCreditService.getBalance(trips[0]!.userId, group.id)).toBe(-300);
  });

  test.each([null, 'connection lost', {}, { code: 123 }])('a non-duplicate balance failure preserves the ledger for retry: %p', async failure => {
    const { group, meetup, trips } = await setup();
    jest.spyOn(FairCreditModel, 'updateOne').mockRejectedValueOnce(failure);
    await expect(fairCreditService.settleMeetup(group.id, meetup.id, trips, true)).rejects.toBe(failure);
    expect(await FairCreditEntryModel.countDocuments()).toBe(2);
    expect(await FairCreditModel.countDocuments()).toBe(0);
    await fairCreditService.settleMeetup(group.id, meetup.id, trips, true);
    expect(await FairCreditEntryModel.countDocuments()).toBe(2);
    expect(await fairCreditService.getBalance(trips[1]!.userId, group.id)).toBe(300);
  });

  // Input: valid settlement
  // Expected behavior: rejects and no balance moves when the ledger cannot be written
  // Mocked behavior: FairCreditEntryModel.insertMany rejects
  test('Ledger write failure leaves balances untouched', async () => {
    const { group, meetup, trips } = await setup();
    jest.spyOn(FairCreditEntryModel, 'insertMany').mockRejectedValue(new Error('db down') as never);

    await expect(fairCreditService.settleMeetup(group.id, meetup.id, trips)).rejects.toThrow('db down');
    expect(await FairCreditModel.countDocuments()).toBe(0);
  });

  // Input: valid settlement
  // Expected behavior: the error propagates; the ledger rows already written still record what is owed,
  //                    and the meetup cannot be settled a second time
  // Mocked behavior: FairCreditModel.updateOne rejects while initializing a balance
  test('Balance write failure', async () => {
    const { group, meetup, trips } = await setup();
    const writeBalance = jest.spyOn(FairCreditModel, 'updateOne').mockRejectedValue(new Error('db down') as never);

    await expect(fairCreditService.settleMeetup(group.id, meetup.id, trips)).rejects.toThrow('db down');
    expect(writeBalance).toHaveBeenCalledTimes(1);
    expect(await FairCreditModel.countDocuments()).toBe(0);
    expect(await FairCreditEntryModel.countDocuments()).toBe(2);
    jest.restoreAllMocks();
    await expect(fairCreditService.settleMeetup(group.id, meetup.id, trips)).rejects.toMatchObject({
      status: 409, message: 'This meetup is already settled',
    });
  });

  // Input: a stored legacy snapshot without commute provenance.
  // Expected: real credit calculations preserve its members/durations; unknown provenance
  // is conservatively recorded as ESTIMATED, and retry cannot apply the balance twice.
  test('Legacy snapshot without source records estimated provenance without inventing participants', async () => {
    const { group, meetup, trips } = await setup();
    await SettlementModel.collection.insertOne({ meetupId: meetup._id, groupId: group._id,
      trips: trips.map(({ source: _source, ...trip }) => trip) });
    const snapshot = await SettlementModel.findOne({ meetupId: meetup.id }).orFail();
    await fairCreditService.settleMeetup(group.id, meetup.id, snapshot.trips, true);
    const rows = await FairCreditEntryModel.find({ meetupId: meetup.id }).lean();
    expect(rows.map(row => String(row.userId))).toEqual(trips.map(trip => trip.userId));
    expect(rows.map(row => ({ source: row.source, seconds: row.actualSeconds }))).toEqual([
      { source: 'ESTIMATED', seconds: 600 }, { source: 'ESTIMATED', seconds: 1200 },
    ]);
    await fairCreditService.settleMeetup(group.id, meetup.id, snapshot.trips, true);
    expect(await FairCreditEntryModel.countDocuments()).toBe(2);
    expect(await fairCreditService.getBalance(trips[0]!.userId, group.id)).toBe(-300);
  });
});

