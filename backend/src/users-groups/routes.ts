import { Router } from 'express';

import { getUserId } from '../common/middleware/require-auth';
import { parseInput } from '../common/middleware/validate';
import { groupService } from './group.service';
import { createGroupBody, groupParams, joinGroupBody } from './schemas';
import { userService } from './user.service';

export const usersGroupsRouter = Router();

// Return authenticated user's profile
usersGroupsRouter.get('/users/me', async (req, res) => {
  res.json(await userService.getById(getUserId(req)));
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
