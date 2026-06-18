import type { FastifyInstance, FastifyReply } from 'fastify';
import type { AppSettingsLinksResponse } from '@carcommunity/shared/onboarding';

/**
 * Placeholder KCC website URLs.
 * TODO: Replace with real production URLs once the KCC website is live.
 * Do NOT include secrets, internal admin URLs, or environment-specific values.
 */
const APP_SETTINGS_LINKS = [
  {
    key: 'support',
    label: 'Support',
    url: 'https://kungsbackacc.se/support', // TODO: Replace with real KCC support URL
  },
  {
    key: 'terms',
    label: 'Terms',
    url: 'https://kungsbackacc.se/villkor', // TODO: Replace with real KCC terms URL
  },
  {
    key: 'privacy_policy',
    label: 'Privacy policy',
    url: 'https://kungsbackacc.se/integritetspolicy', // TODO: Replace with real KCC privacy policy URL
  },
  {
    key: 'account_deletion_info',
    label: 'Account deletion',
    url: 'https://kungsbackacc.se/konto/radera', // TODO: Replace with real KCC account deletion URL
  },
  {
    key: 'data_deletion_info',
    label: 'Data deletion',
    url: 'https://kungsbackacc.se/konto/data', // TODO: Replace with real KCC data deletion URL
  },
  {
    key: 'github',
    label: 'GitHub / Open Source',
    url: 'https://github.com/SebMcCayen/carcommunity',
  },
  {
    key: 'open_source_licenses',
    label: 'Open source licenses',
    url: 'https://github.com/SebMcCayen/carcommunity/blob/main/LICENSE',
  },
] as const;

export async function registerAppSettingsRoutes(app: FastifyInstance): Promise<void> {
  /**
   * GET /v1/app/settings-links — returns safe public links for the mobile app settings screen.
   * Public endpoint; no secrets or internal URLs.
   */
  app.get(
    '/v1/app/settings-links',
    async (_request, reply: FastifyReply): Promise<void> => {
      await reply.code(200).send({
        ok: true,
        data: {
          links: APP_SETTINGS_LINKS.map((link) => ({ ...link })),
        },
      } satisfies AppSettingsLinksResponse);
    },
  );
}
