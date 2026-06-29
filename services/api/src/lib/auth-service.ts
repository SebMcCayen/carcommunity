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
  onboardingCompletedAt: Date | null;
}

export interface CreatedSession {
  sessionId: string;
  expiresAt: Date;
  token: TokenResponsePlaceholder;
}

export interface AuthService {
  findOrCreateUserByProviderIdentity(input: ProviderIdentityLoginInput): Promise<AuthenticatedSession['user']>;
  findOrCreateUserByFirebaseUid(firebaseUid: string, email?: string | null): Promise<AuthenticatedSession['user']>;
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
  status: UserStatus;
  subscriptionEntitlement: SubscriptionEntitlement;
  displayName: string | null;
  identities: Array<{ provider: AuthProvider; providerSubject: string }>;
  onboardingCompletedAt?: Date | null;
}): AuthenticatedUserSummary {
  return {
    userId: input.id,
    displayName: input.displayName,
    identities: input.identities,
    roles: [input.role],
    status: input.status,
    subscriptionEntitlement: input.subscriptionEntitlement,
    onboardingCompletedAt: input.onboardingCompletedAt ? input.onboardingCompletedAt.toISOString() : null,
  };
}

export function createAuthService(prisma: PrismaClient): AuthService {
  return {
    async findOrCreateUserByFirebaseUid(firebaseUid, email) {
      const trimmedUid = firebaseUid.trim();
      const normalizedEmail = email?.trim().toLowerCase() ?? null;

      const user = await prisma.$transaction(async (tx) => {
        const existing = await tx.user.findUnique({
          where: { firebaseUid: trimmedUid },
          include: { identities: true },
        });

        if (existing) {
          return existing;
        }

        // Create a new user record linked to this Firebase UID.
        // Role and status default to 'user' / 'active' — admin access is
        // determined solely from the Firebase custom claim, not the DB role.
        const created = await tx.user.create({
          data: {
            firebaseUid: trimmedUid,
            email: normalizedEmail,
          },
          include: { identities: true },
        });

        return created;
      });

      return buildAuthenticatedUserSummary({
        id: user.id,
        displayName: user.displayName,
        role: user.role,
        status: user.status,
        subscriptionEntitlement: user.subscriptionEntitlement,
        identities: user.identities.map((identity) => ({
          provider: identity.provider,
          providerSubject: identity.providerSubject,
        })),
        onboardingCompletedAt: user.onboardingCompletedAt,
      });
    },

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
        status: user.status,
        subscriptionEntitlement: user.subscriptionEntitlement,
        identities: user.identities.map((identity) => ({
          provider: identity.provider,
          providerSubject: identity.providerSubject,
        })),
        onboardingCompletedAt: user.onboardingCompletedAt,
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
        onboardingCompletedAt: session.user.onboardingCompletedAt,
        user: buildAuthenticatedUserSummary({
          id: session.user.id,
          displayName: session.user.displayName,
          role: session.user.role,
          status: session.user.status,
          subscriptionEntitlement: session.user.subscriptionEntitlement,
          identities: session.user.identities.map((identity) => ({
            provider: identity.provider,
            providerSubject: identity.providerSubject,
          })),
          onboardingCompletedAt: session.user.onboardingCompletedAt,
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

  const trimmed = authorizationHeader.trim();
  if (trimmed.length < 8 || !trimmed.toLowerCase().startsWith('bearer ')) {
    return null;
  }

  const token = trimmed.slice('bearer '.length).trim();
  return token ? token : null;
}
