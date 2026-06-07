import { defineConfig } from 'prisma/config';

const databaseUrl =
  process.env.DATABASE_URL ??
  'postgresql://placeholder@example.invalid:5432/carcommunity_api?schema=public';

process.env.DATABASE_URL = databaseUrl;

export default defineConfig({
  schema: './prisma/schema.prisma',
});
