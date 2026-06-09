import { createHash, randomBytes } from 'node:crypto';

import type { AuthProvider, AuthenticatedUserSummary, TokenResponsePlaceholder } from '@carcommunity/shared/auth';
import type { SubscriptionEntitlement, UserRole, UserStatus } from '@carcommunity/shared/users';
import type { IdentityProvider, PrismaClient } from '@prisma/client';

const SESSION_TTL_SECONDS = 60 * 60 * 24 * 30;

export interface ProviderIdentityLoginInput {
  provider: AuthProvider;
  providerSubject: string;
  providerEmail?: string | null;
  displayName?: string | null;
}

export interface AuthenticatedSession {
  sessionId: string;
  userId: string;
  role: UserRole;
  status: UserStatus;
  subscriptionEntitlement: SubscriptionEntitlement;
  displayName: string | null;
  lastActiveAt: Date | null;
  expiresAt: Date;
  user: AuthenticatedUserSummary;
}

export interface CreatedSession {
  sessionId: string;
  expiresAt: Date;
  token: TokenResponsePlaceholder;
}

export interface AuthService {
  findOrCreateUserByProviderIdentity(input: ProviderIdentityLoginInput): Promise<AuthenticatedSession['user']>;
  createSession(userId: string): Promise<CreatedSession>;
  lookupSession(rawToken: string): Promise<AuthenticatedSession | null>;
  revokeSession(rawToken: string): Promise<boolean>;
}

function hashSessionToken(rawToken: string): string {
  return createHash('sha256').update(rawToken).digest('hex');
}

function buildAuthenticatedUserSummary(input: {
  id: string;
  role: UserRole;
  displayName: string | null;
  identities: Array<{ provider: AuthProvider; providerSubject: string }>;
}): AuthenticatedUserSummary {
  return {
    userId: input.id,
    displayName: input.displayName,
    identities: input.identities,
    roles: [input.role],
  };
}

export function createAuthService(prisma: PrismaClient): AuthService {
  return {
    async findOrCreateUserByProviderIdentity(input) {
      const provider = input.provider as IdentityProvider;
      const providerSubject = input.providerSubject.trim();
      const providerEmail = input.providerEmail?.trim().toLowerCase() ?? null;
      const displayName = input.displayName?.trim() ?? null;

      const user = await prisma.$transaction(async (tx) => {
        const existingIdentity = await tx.userIdentity.findUnique({
          where: {
            provider_providerSubject: { provider, providerSubject },
          },
          include: {
            user: {
              include: {
                identities: true,
              },
            },
          },
        });

        if (existingIdentity) {
          return existingIdentity.user;
        }

        const createdUser = await tx.user.create({
          data: {
            displayName,
            email: providerEmail,
          },
        });

        await tx.userIdentity.create({
          data: {
            userId: createdUser.id,
            provider,
            providerSubject,
            providerEmail,
          },
        });

        return tx.user.findUniqueOrThrow({
          where: { id: createdUser.id },
          include: { identities: true },
        });
      });

      return buildAuthenticatedUserSummary({
        id: user.id,
        displayName: user.displayName,
        role: user.role,
        identities: user.identities.map((identity) => ({
          provider: identity.provider,
          providerSubject: identity.providerSubject,
        })),
      });
    },

    async createSession(userId) {
      const rawToken = `dev_session_${randomBytes(24).toString('base64url')}`;
      const now = Date.now();
      const expiresAt = new Date(now + SESSION_TTL_SECONDS * 1000);

      const session = await prisma.session.create({
        data: {
          userId,
          tokenHash: hashSessionToken(rawToken),
          expiresAt,
          lastUsedAt: new Date(now),
        },
      });

      return {
        sessionId: session.id,
        expiresAt: session.expiresAt,
        token: {
          _devOnly: true,
          accessToken: rawToken,
          expiresIn: SESSION_TTL_SECONDS,
        },
      };
    },

    async lookupSession(rawToken) {
      const tokenHash = hashSessionToken(rawToken);
      const now = new Date();
      const session = await prisma.session.findFirst({
        where: {
          tokenHash,
          revokedAt: null,
          expiresAt: {
            gt: now,
          },
        },
        include: {
          user: {
            include: {
              identities: true,
            },
          },
        },
      });

      if (!session) {
        return null;
      }

      await prisma.$transaction([
        prisma.session.update({
          where: { id: session.id },
          data: { lastUsedAt: now },
        }),
        prisma.user.update({
          where: { id: session.userId },
          data: { lastActiveAt: now },
        }),
      ]);

      return {
        sessionId: session.id,
        userId: session.user.id,
        role: session.user.role,
        status: session.user.status,
        subscriptionEntitlement: session.user.subscriptionEntitlement,
        displayName: session.user.displayName,
        lastActiveAt: now,
        expiresAt: session.expiresAt,
        user: buildAuthenticatedUserSummary({
          id: session.user.id,
          displayName: session.user.displayName,
          role: session.user.role,
          identities: session.user.identities.map((identity) => ({
            provider: identity.provider,
            providerSubject: identity.providerSubject,
          })),
        }),
      } satisfies AuthenticatedSession;
    },

    async revokeSession(rawToken) {
      const tokenHash = hashSessionToken(rawToken);
      const result = await prisma.session.updateMany({
        where: {
          tokenHash,
          revokedAt: null,
        },
        data: {
          revokedAt: new Date(),
        },
      });

      return result.count > 0;
    },
  };
}

export function parseBearerToken(authorizationHeader: string | undefined): string | null {
  if (!authorizationHeader) return null;

  const match = /^Bearer\s+(.+)$/i.exec(authorizationHeader.trim());
  if (!match) {
    return null;
  }

  const token = match[1]?.trim();
  return token ? token : null;
}
