import type { FastifyInstance, FastifyReply } from 'fastify';
import type { AppSettingsLinksResponse } from '@carcommunity/shared/onboarding';

import { brandLinks } from '../lib/brand-config.js';

/**
 * App settings links built from brand configuration.
 * Override defaults with environment variables (BRAND_*_URL) for multi-brand deployments.
 * Do NOT include secrets, internal admin URLs, or environment-specific values.
 */
const APP_SETTINGS_LINKS = [
  { key: 'support', label: 'Support', url: brandLinks.support },
  { key: 'terms', label: 'Terms', url: brandLinks.terms },
  { key: 'privacy_policy', label: 'Privacy policy', url: brandLinks.privacyPolicy },
  { key: 'account_deletion_info', label: 'Account deletion', url: brandLinks.accountDeletion },
  { key: 'data_deletion_info', label: 'Data deletion', url: brandLinks.dataDeletion },
  { key: 'github', label: 'GitHub / Open Source', url: brandLinks.github },
  { key: 'open_source_licenses', label: 'Open source licenses', url: brandLinks.licenses },
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
