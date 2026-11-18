import { Router } from 'express';

import { asyncHandler } from '../common/middleware/error-handler';
import { getUserId } from '../common/middleware/require-auth';
import { parseInput } from '../common/middleware/validate';
import { deletionService } from './deletion.service';
import { groupService } from './group.service';
import { meetupService } from './meetup.service';
import {
  createGroupBody,
  createMeetupBody,
  groupMemberParams,
  groupParams,
  joinGroupBody,
  meetupParams,
  participationBody,
} from './schemas';
import { userService } from './user.service';

export const usersGroupsRouter = Router();

// ---- Users ----

// The signed-in user's account
usersGroupsRouter.get('/users/me', asyncHandler(async (req, res) => {
  res.json(await userService.getById(getUserId(req)));
}));

// ---- Groups ----

// Create a group; the caller becomes its organizer
usersGroupsRouter.post('/groups', asyncHandler(async (req, res) => {
  const { name } = parseInput(createGroupBody, req.body);
  res.status(201).json(await groupService.create(getUserId(req), name));
}));

usersGroupsRouter.get('/groups', asyncHandler(async (req, res) => {
  res.json(await groupService.listForUser(getUserId(req)));
}));

// Registered before /groups/:groupId so "join" is not read as a group id
usersGroupsRouter.post('/groups/join', asyncHandler(async (req, res) => {
  const { inviteCode } = parseInput(joinGroupBody, req.body);
  res.json(await groupService.join(getUserId(req), inviteCode));
}));

usersGroupsRouter.get('/groups/:groupId', asyncHandler(async (req, res) => {
  const { groupId } = parseInput(groupParams, req.params);
  res.json(await groupService.getDetail(groupId, getUserId(req)));
}));

// Group organizer only; also deletes the group's meetups and everything that belongs to them
usersGroupsRouter.delete('/groups/:groupId', asyncHandler(async (req, res) => {
  const { groupId } = parseInput(groupParams, req.params);
  await deletionService.deleteGroup(groupId, getUserId(req));
  res.status(204).end();
}));

// The caller leaves the group. Registered before /members/:memberId so "me" is not read as a member id
usersGroupsRouter.delete('/groups/:groupId/members/me', asyncHandler(async (req, res) => {
  const { groupId } = parseInput(groupParams, req.params);
  await groupService.leave(groupId, getUserId(req));
  res.status(204).end();
}));

// Group organizer only; removes another member
usersGroupsRouter.delete('/groups/:groupId/members/:memberId', asyncHandler(async (req, res) => {
  const { groupId, memberId } = parseInput(groupMemberParams, req.params);
  await groupService.removeMember(groupId, getUserId(req), memberId);
  res.status(204).end();
}));

// ---- Meetups ----

// The caller becomes the organizer of the new meetup
usersGroupsRouter.post('/groups/:groupId/meetups', asyncHandler(async (req, res) => {
  const { groupId } = parseInput(groupParams, req.params);
  const input = parseInput(createMeetupBody, req.body);
  res.status(201).json(await meetupService.create(getUserId(req), groupId, input));
}));

usersGroupsRouter.get('/groups/:groupId/meetups', asyncHandler(async (req, res) => {
  const { groupId } = parseInput(groupParams, req.params);
  res.json(await meetupService.listForGroup(groupId, getUserId(req)));
}));

usersGroupsRouter.get('/meetups/:meetupId', asyncHandler(async (req, res) => {
  const { meetupId } = parseInput(meetupParams, req.params);
  res.json(await meetupService.getForMember(meetupId, getUserId(req)));
}));

// Meetup organizer only
usersGroupsRouter.delete('/meetups/:meetupId', asyncHandler(async (req, res) => {
  const { meetupId } = parseInput(meetupParams, req.params);
  await deletionService.deleteMeetup(meetupId, getUserId(req));
  res.status(204).end();
}));

// Add or replace the caller's start location, travel mode and preferences
usersGroupsRouter.put('/meetups/:meetupId/participation', asyncHandler(async (req, res) => {
  const { meetupId } = parseInput(meetupParams, req.params);
  const input = parseInput(participationBody, req.body);
  res.json(await meetupService.submitParticipation(meetupId, getUserId(req), input));
}));

// The caller withdraws from the meetup (only before voting starts)
usersGroupsRouter.delete('/meetups/:meetupId/participation', asyncHandler(async (req, res) => {
  const { meetupId } = parseInput(meetupParams, req.params);
  await meetupService.withdrawParticipation(meetupId, getUserId(req));
  res.status(204).end();
}));
