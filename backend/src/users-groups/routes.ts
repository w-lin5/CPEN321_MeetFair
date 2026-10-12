import { Router } from 'express';

import { getUserId } from '../common/middleware/require-auth';
import { userService } from './user.service';

export const usersGroupsRouter = Router();

// Return authenticated user's profile
usersGroupsRouter.get('/users/me', async (req, res) => {
  res.json(await userService.getById(getUserId(req)));
});
