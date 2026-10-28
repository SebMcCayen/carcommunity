# Public app information — owner review and deployment pending

The App Store audit found that `/privacy` and `/terms` returned the admin SPA,
which was empty without authentication. `apps/admin/public/{privacy,terms,support}/index.html`
now supplies standalone Swedish/English HTML. Vite copies these files to `dist`.
Firebase Hosting serves static content before the existing SPA fallback; no
Firebase configuration, service, analytics, dependency or paid plan is added.
The normal admin entry page and authenticated routes remain unchanged.

## Do not merge/deploy this draft yet

The existing Hosting workflow automatically deploys changes under `apps/admin`
on merge. Production deployment can be billable. Owner approval is required
before merge; the draft status is intentional, even with green checks.

Before publishing, the service operator must:

1. The owner selected **Kungsbacka Car Community** as the public operator name.
   Confirm its legal form, public service/contact address and any organisation
   information, and approve the published contact mailbox.
2. Confirm legal bases, recipients/contracts, international-transfer safeguards,
   precise retention periods and legal reasons for exceptions to account erasure.
   Pseudonymous records are not automatically anonymous. Specify an erasure
   completion-confirmation process, moderation ownership, age requirements and
   effective date. The public text visibly calls these decisions unresolved.
3. Review the bilingual text against the deployed services. Deploy the account
   erasure, explicit partner-consent and content-reporting changes first. Do not
   publish future-tense assurances as current behaviour.
4. Replace the draft notice with approved operator details/date in both languages,
   and update the explicit draft regression test accordingly. Remove `noindex`
   only after approval. Obtain permission for the existing production Hosting
   deployment; no new service is required by these pages.
5. Check the live `/privacy`, `/privacy/`, `/terms`, `/terms/` and `/support` URLs
   in an unauthenticated/private browser after deploy. Confirm they show the
   information pages, not the Admin Portal or a sign-in screen. Put these URLs
   in App Store Connect and verify them from the signed app.

## Checks

`publicLegalPages.test.ts` parses real static documents and checks readable content,
English sections, local navigation/anchor/asset integrity, contact access and the
absence of script, login forms and remote assets. Run the ordinary admin tests
and Vite production build. Then use **only** the Hosting emulator on a `demo-*`
project to verify directory URLs and SPA fallthrough against the built output.
Do not use a production project, deploy command or credentialed cloud API for
this check. The app's `/privacy` and `/terms` URLs intentionally stay unchanged.

Provider routing reference: https://firebase.google.com/docs/hosting/full-config#hosting_priority_order

This is an implementation draft and release checklist, not a legal sign-off.
