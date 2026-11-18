import express, { type Express, Router } from 'express';

import { errorHandler } from './common/middleware/error-handler';
import { requireAuth } from './common/middleware/require-auth';
import { recommendationService } from './recommendation/recommendation.service';
import { recommendationRouter } from './recommendation/routes';
import { authRouter } from './users-groups/auth.routes';
import { setDependentDataOwners } from './users-groups/dependent-data';
import { usersGroupsRouter } from './users-groups/routes';
import { votingFairnessCleanup } from './voting-fairness/cleanup.service';
import { votingFairnessRouter } from './voting-fairness/routes';

export function createApp(): Express {
  // Components that store data per meetup, group or member tell users-groups how to clean it up,
  // because users-groups (which deletes those) must not depend on them
  setDependentDataOwners([{ meetupsDeleted: recommendationService.deleteForMeetups }, votingFairnessCleanup]);

  const app = express();
  app.use(express.json());

  app.get('/health', (_req, res) => {
    res.json({ status: 'ok' });
  });

  const api = Router();
  api.use('/auth', authRouter);
  api.use(requireAuth);
  api.use(usersGroupsRouter);
  api.use(recommendationRouter);
  api.use(votingFairnessRouter);
  app.use('/api', api);

  app.use((_req, res) => {
    res.status(404).json({ error: 'Not Found' });
  });
  app.use(errorHandler);

  return app;
}
