import { loadConfig } from './config.js';
import { createServer } from './server.js';

const config = loadConfig();
const app = await createServer(config);

const closeServer = async (signal: string): Promise<void> => {
  app.log.info({ signal }, 'Shutting down API server.');
  await app.close();
  process.exit(0);
};

process.on('SIGINT', () => {
  void closeServer('SIGINT');
});

process.on('SIGTERM', () => {
  void closeServer('SIGTERM');
});

try {
  await app.listen({
    host: '0.0.0.0',
    port: config.port,
  });
} catch (error) {
  app.log.error(error, 'Failed to start API server.');
  process.exit(1);
}
