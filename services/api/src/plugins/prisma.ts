import { PrismaClient } from '@prisma/client';
import type { FastifyInstance } from 'fastify';

import type { AppConfig } from '../config.js';

declare module 'fastify' {
  interface FastifyInstance {
    prisma: PrismaClient;
  }
}

export async function registerPrisma(app: FastifyInstance, config: AppConfig): Promise<void> {
  const prisma = new PrismaClient({
    datasourceUrl: config.databaseUrl,
  });

  app.decorate('prisma', prisma);

  app.addHook('onClose', async (instance) => {
    await instance.prisma.$disconnect();
  });
}
