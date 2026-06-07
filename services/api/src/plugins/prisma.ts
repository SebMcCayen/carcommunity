import { PrismaClient } from '@prisma/client';
import { PrismaPg } from '@prisma/adapter-pg';
import type { FastifyInstance } from 'fastify';

import type { AppConfig } from '../config.js';

declare module 'fastify' {
  interface FastifyInstance {
    prisma: PrismaClient;
  }
}

export async function registerPrisma(app: FastifyInstance, config: AppConfig): Promise<void> {
  const adapter = new PrismaPg({ connectionString: config.databaseUrl });
  const prisma = new PrismaClient({ adapter });

  app.decorate('prisma', prisma);

  app.addHook('onClose', async (instance) => {
    await instance.prisma.$disconnect();
  });
}
