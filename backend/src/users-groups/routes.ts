import { Router } from 'express';

import { getUserId } from '../common/middleware/require-auth';
import { parseInput } from '../common/middleware/validate';
import { groupService } from './group.service';
import { meetupService } from './meetup.service';
import { profileService } from './profile.service';
import {
  createGroupBody,
  createMeetupBody,
  groupParams,
  joinGroupBody,
  meetupParams,
  participationBody,
  updateProfileBody,
} from './schemas';
import { userService } from './user.service';

export const usersGroupsRouter = Router();

// Return authenticated user's profile
usersGroupsRouter.get('/users/me', async (req, res) => {
  res.json(await userService.getById(getUserId(req)));
});

// Account info, groups and per-group fairness debt in one response
usersGroupsRouter.get('/users/me/profile', async (req, res) => {
  res.json(await profileService.getProfile(getUserId(req)));
});

// Replaces the editable profile fields (currently only displayName)
usersGroupsRouter.put('/users/me/profile', async (req, res) => {
  const input = parseInput(updateProfileBody, req.body);
  res.json(await profileService.updateProfile(getUserId(req), input));
});

// The caller's fairness debt and commute history in one of their groups
usersGroupsRouter.get('/users/me/groups/:groupId/fairness', async (req, res) => {
  const { groupId } = parseInput(groupParams, req.params);
  res.json(await profileService.getFairnessHistory(getUserId(req), groupId));
});

// Create a group; the caller becomes its organizer
usersGroupsRouter.post('/groups', async (req, res) => {
  const { name } = parseInput(createGroupBody, req.body);
  res.status(201).json(await groupService.create(getUserId(req), name));
});

usersGroupsRouter.get('/groups', async (req, res) => {
  res.json(await groupService.listForUser(getUserId(req)));
});

// Registered before /groups/:groupId so "join" is not read as a group id
usersGroupsRouter.post('/groups/join', async (req, res) => {
  const { inviteCode } = parseInput(joinGroupBody, req.body);
  res.json(await groupService.join(getUserId(req), inviteCode));
});

usersGroupsRouter.get('/groups/:groupId', async (req, res) => {
  const { groupId } = parseInput(groupParams, req.params);
  res.json(await groupService.getDetail(groupId, getUserId(req)));
});

// The caller becomes the organizer of the new meetup
usersGroupsRouter.post('/groups/:groupId/meetups', async (req, res) => {
  const { groupId } = parseInput(groupParams, req.params);
  const input = parseInput(createMeetupBody, req.body);
  res.status(201).json(await meetupService.create(getUserId(req), groupId, input));
});

usersGroupsRouter.get('/groups/:groupId/meetups', async (req, res) => {
  const { groupId } = parseInput(groupParams, req.params);
  res.json(await meetupService.listForGroup(groupId, getUserId(req)));
});

usersGroupsRouter.get('/meetups/:meetupId', async (req, res) => {
  const { meetupId } = parseInput(meetupParams, req.params);
  res.json(await meetupService.getForMember(meetupId, getUserId(req)));
});

usersGroupsRouter.put('/meetups/:meetupId/participation', async (req, res) => {
  const { meetupId } = parseInput(meetupParams, req.params);
  const input = parseInput(participationBody, req.body);
  res.json(await meetupService.submitParticipation(meetupId, getUserId(req), input));
});
