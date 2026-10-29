# Public app information — owner review and deployment pending

`apps/admin/public/{privacy,terms,support}/index.html` supplies standalone
Swedish/English HTML. Vite copies these files to `dist`; static Hosting files take
precedence over the admin SPA fallback. No service or Firebase configuration is added.

## Confirmed owner information — 2026-10-29

The controller/operator is **Sebastian Stegrin**, acting as an individual under
**Kungsbacka Car Community**. Contact: **kontakt@kungsbackacarcommunity.se**.
Minimum service age is **18**. App administrators handle moderation; review
requests go by email. These facts are now included in both languages.

The owner requests an Android-1.0 effective date. The in-app catalogue records
1.0.0 on 2026-09-02, but public Play availability on that date is not verified.
Record the actual launch separately from this new text's publication/revision and
effective dates. Do not backdate new disclosures, acceptance or consent.

The owner supplied an email contact address, not a postal address. No address or
organisation number is invented. Confirm any postal/trader-contact requirements
for paid services and App Store Connect separately; individual status alone does
not decide trader status.

## Before merge/publication

1. Review the proposed purpose/legal-basis allocation in
   [the code-backed processing review](privacy-processing-review-2026-10-29.md).
   Complete the necessity/balancing assessments, applicable provider agreements
   and transfer safeguards. General provider documentation is not proof of the
   operator's acceptance or configuration.
2. Resolve the documented retention/erasure gaps. Source code does not establish
   a complete retention plan or a deletion-completion acknowledgement. Define and
   verify specific bounds and the actual administrator response process; do not
   turn an implementation gap into an indefinite legal retention rule.
3. Verify deployed account erasure, explicit partner consent (#1245), reporting
   and external TTL policies before publishing their descriptions. A merged file
   or green emulator test is not proof of production activation. Current Android
   still has default-on partner wording pending #1245.
4. Confirm the effective/publication dates and final bilingual wording. Only then
   replace draft notices and remove `noindex`; keep the draft guard until then.
5. Merge/publication approval must cover the existing production Hosting workflow:
   changes under `apps/admin` automatically deploy on merge and can be billable.
   This documentation update does not deploy, migrate data or change either app.
6. After an approved deploy, check `/privacy`, `/privacy/`, `/terms`, `/terms/`,
   `/support` and `/support/` without login. Confirm readable information rather
   than the admin SPA; verify the URLs from each released app and App Store Connect.

## Local checks

Run `publicLegalPages.test.ts`, bounded lint, admin typecheck and the Vite build.
The document tests check script-free readable pages, bilingual navigation, local
assets, contact access, confirmed operator details and explicit draft status.
A Hosting routing check uses only the emulator on a `demo-*` project, never a
production project or credentialed API. The app's policy/terms URLs stay unchanged.

Provider routing reference:
https://firebase.google.com/docs/hosting/full-config#hosting_priority_order

This is an implementation draft and release checklist, not a legal sign-off.
