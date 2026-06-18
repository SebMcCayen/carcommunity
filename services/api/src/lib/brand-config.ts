/**
 * Brand-level configuration: public website links used in the app settings endpoint.
 *
 * These values default to the KCC (Kungsbacka Car Community) website and can be
 * overridden via environment variables to support future multi-brand deployments.
 *
 * No secrets — only public-facing URLs are stored here.
 */

export const brandLinks = {
  support: process.env['BRAND_SUPPORT_URL'] ?? 'https://kungsbackacc.se/support',
  terms: process.env['BRAND_TERMS_URL'] ?? 'https://kungsbackacc.se/villkor',
  privacyPolicy: process.env['BRAND_PRIVACY_POLICY_URL'] ?? 'https://kungsbackacc.se/integritetspolicy',
  accountDeletion: process.env['BRAND_ACCOUNT_DELETION_URL'] ?? 'https://kungsbackacc.se/konto/radera',
  dataDeletion: process.env['BRAND_DATA_DELETION_URL'] ?? 'https://kungsbackacc.se/konto/data',
  github: process.env['BRAND_GITHUB_URL'] ?? 'https://github.com/SebMcCayen/carcommunity',
  licenses: process.env['BRAND_LICENSES_URL'] ?? 'https://github.com/SebMcCayen/carcommunity/blob/main/LICENSE',
} as const;
