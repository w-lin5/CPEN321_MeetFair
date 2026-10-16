import express, { type Express, Router } from 'express';

import { errorHandler } from './common/middleware/error-handler';
import { requireAuth } from './common/middleware/require-auth';
import { googleCalendarRouter } from './integrations/google-calendar.routes';
import { authRouter } from './users-groups/auth.routes';
import { usersGroupsRouter } from './users-groups/routes';

export function createApp(): Express {
  const app = express();
  app.use(express.json());

  app.get('/health', (_req, res) => {
    res.json({ status: 'ok' });
  });

  const api = Router();
  api.use('/auth', authRouter);
  api.use(requireAuth);
  api.use(usersGroupsRouter);
  api.use('/integrations/google-calendar', googleCalendarRouter);
  app.use('/api', api);

  app.use((_req, res) => {
    res.status(404).json({ error: 'Not Found' });
  });
  app.use(errorHandler);

  return app;
}
