import mongoose from 'mongoose';

import { connectDb, disconnectDb } from '../../src/db';

afterEach(() => jest.restoreAllMocks());

// Interface: connectDb / disconnectDb
describe('Mocked: database connection', () => {
  // Input: a MongoDB URI
  // Expected behavior: the URI is handed to mongoose unchanged
  // Mocked behavior: mongoose.connect resolves
  test('connectDb connects with the given URI', async () => {
    const connect = jest.spyOn(mongoose, 'connect').mockResolvedValue(mongoose);

    await connectDb('mongodb://example/meetfair');

    expect(connect).toHaveBeenCalledWith('mongodb://example/meetfair');
  });

  // Input: a MongoDB URI with models registered
  // Expected behavior: connectDb resolves only after every model has built its indexes
  // Mocked behavior: mongoose.connect resolves; one registered model whose init resolves
  test('connectDb waits for model indexes', async () => {
    jest.spyOn(mongoose, 'connect').mockResolvedValue(mongoose);
    const init = jest.fn().mockResolvedValue(undefined);
    jest.spyOn(mongoose, 'modelNames').mockReturnValue(['Vote']);
    jest.spyOn(mongoose, 'model').mockReturnValue({ init } as never);

    await connectDb('mongodb://example/meetfair');

    expect(init).toHaveBeenCalledTimes(1);
  });

  // Input: unreachable database
  // Expected behavior: the failure propagates so startup can abort
  // Mocked behavior: mongoose.connect rejects
  test('connectDb propagates connection failures', async () => {
    jest.spyOn(mongoose, 'connect').mockRejectedValue(new Error('unreachable'));

    await expect(connectDb('mongodb://example/meetfair')).rejects.toThrow('unreachable');
  });

  // Input: open connection
  // Expected behavior: mongoose is asked to disconnect
  // Mocked behavior: mongoose.disconnect resolves
  test('disconnectDb disconnects', async () => {
    const disconnect = jest.spyOn(mongoose, 'disconnect').mockResolvedValue();

    await disconnectDb();

    expect(disconnect).toHaveBeenCalledTimes(1);
  });
});
