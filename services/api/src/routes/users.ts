import type { FastifyInstance } from 'fastify';
import type { AdminUsersResponse, CurrentUserResponse } from '@carcommunity/shared/users';

const PLACEHOLDER_USER = {
  id: '00000000-0000-4000-8000-000000000001',
  displayName: 'Community Member',
  email: null,
  role: 'user',
  status: 'active',
  subscriptionEntitlement: 'none',
  lastActiveAt: null,
  createdAt: '2026-01-01T00:00:00.000Z',
  updatedAt: '2026-01-01T00:00:00.000Z',
} as const;

export async function registerUserRoutes(app: FastifyInstance): Promise<void> {
  app.get('/v1/users/me', async (): Promise<CurrentUserResponse> => {
    return {
      ok: true,
      data: {
        user: PLACEHOLDER_USER,
      },
    };
  });

  app.get('/v1/admin/users', async (): Promise<AdminUsersResponse> => {
    // TODO: Enforce backend-verified admin role before returning any admin data.
    // TODO: Never trust client-side admin flags for authorization.
    return {
      ok: true,
      data: {
        users: [PLACEHOLDER_USER],
      },
      meta: {
        page: 1,
        pageSize: 1,
        total: 1,
        hasNext: false,
      },
    };
  });
}
