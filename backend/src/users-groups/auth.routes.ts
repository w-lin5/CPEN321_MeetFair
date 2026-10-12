import { Router } from 'express';

import { parseInput } from '../common/middleware/validate';
import { googleSignInBody } from './schemas';
import { userService } from './user.service';

export const authRouter = Router();

// Exchange verified Google ID token for an application session
authRouter.post('/google', async (req, res) => {
  const { idToken } = parseInput(googleSignInBody, req.body);
  res.json(await userService.signInWithGoogle(idToken));
});
