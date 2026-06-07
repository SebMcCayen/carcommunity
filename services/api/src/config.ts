import { z } from 'zod';

export const API_NAME = '@carcommunity/api';
export const API_VERSION = '0.1.0';

const envSchema = z
  .object({
    NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
    API_PORT: z
      .string()
      .default('4000')
      .transform((value, ctx) => {
        const port = Number.parseInt(value, 10);

        if (!Number.isInteger(port) || port < 1 || port > 65_535) {
          ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: 'API_PORT must be a valid TCP port number.',
          });

          return z.NEVER;
        }

        return port;
      }),
    DATABASE_URL: z.string().optional(),
  })
  .superRefine((value, ctx) => {
    if (value.NODE_ENV === 'production' && !value.DATABASE_URL) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        message: 'DATABASE_URL is required in production.',
        path: ['DATABASE_URL'],
      });
    }
  });

export type AppConfig = {
  nodeEnv: 'development' | 'test' | 'production';
  port: number;
  databaseUrl: string;
  isProduction: boolean;
};

export function loadConfig(env: NodeJS.ProcessEnv = process.env): AppConfig {
  const parsed = envSchema.parse(env);

  return {
    nodeEnv: parsed.NODE_ENV,
    port: parsed.API_PORT,
    databaseUrl:
      parsed.DATABASE_URL ??
      'postgresql://' + 'local-user:local-password@localhost:5432/carcommunity_api?schema=public',
    isProduction: parsed.NODE_ENV === 'production',
  };
}
