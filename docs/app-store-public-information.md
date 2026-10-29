# Public app information — one canonical homepage source

## Canonical public pages

The existing homepage is the single editable source for information shared by
CarCommunity and Driver:

- [Privacy](https://kungsbackacarcommunity.se/integritetspolicy.html)
- [Terms](https://kungsbackacarcommunity.se/anvandarvillkor.html)
- [Support](https://kungsbackacarcommunity.se/support.html)
- [Account deletion](https://kungsbackacarcommunity.se/radera-konto.html)
- [Community rules](https://kungsbackacarcommunity.se/community-regler.html)

Their HTML lives in `SebMcCayen/kungsbacka-car-community-homepage`. Correct the
existing pages there rather than maintaining another privacy policy in the admin
build. English sections, where provided, use `#english` on the same page.

## Existing installed-app links

Android onboarding/Settings and existing app metadata use the stable Firebase
Hosting URLs. Three small static compatibility pages route the following six URL forms to the
homepage. Each uses immediate browser navigation (`meta refresh`), a canonical link
and ordinary Swedish/English fallback links; no JavaScript or sign-in is required.
Static files take precedence over the admin SPA rewrite:

| Existing route          | Destination                                                |
| ----------------------- | ---------------------------------------------------------- |
| `/privacy`, `/privacy/` | `https://kungsbackacarcommunity.se/integritetspolicy.html` |
| `/terms`, `/terms/`     | `https://kungsbackacarcommunity.se/anvandarvillkor.html`   |
| `/support`, `/support/` | `https://kungsbackacarcommunity.se/support.html`           |

This fixes the link for already installed apps without an APK, iOS implementation
change, localisation regeneration or new service. The shared Firebase configuration stays unchanged, so this frontend correction
does not trigger the separate Functions deployment workflow. The admin SPA fallback, security headers
and separate public-event Hosting target retain their existing configuration.
The compatibility pages contain navigation only, with no duplicated policy text
or new stylesheet in `apps/admin/public`.

## Confirmed content and scope

The owner confirmed Sebastian Stegrin as the individual operator, contact email
`kontakt@kungsbackacarcommunity.se`, minimum **18 for both apps**, administrator
moderation and email review. Android 1.0's public launch was **2026-09-02**,
confirmed by the owner. The new page revision has a separate update date; new
information/consent is not retrospectively attributed to that launch.

The companion homepage correction reuses the existing published legal-basis text,
fixes the contradictory 13-year age floor and future-only subscription wording,
and removes unsupported 90-day-backup and complete-erasure guarantees. The
[code-backed processing review](privacy-processing-review-2026-10-29.md) records
remaining operational questions. A website correction or redirect does not itself
implement consent, TTL, erasure, policy reacceptance or a retention schedule.

## Rollout and validation

1. Review and merge [homepage PR #18](https://github.com/SebMcCayen/kungsbacka-car-community-homepage/pull/18) first. Confirm its actual deployed
   pages contain the corrected owner/18+ information and current subscriptions,
   preserve Driver-specific differences and avoid unverified retention promises.
2. Require current-head checks and code/security review for the CarCommunity PR.
   Its merge starts the existing Hosting workflow, not the Functions workflow. Deploy/verify the admin target
   without widening the scope to unrelated services; do not publish old draft pages.
3. Test `/privacy`, `/privacy/`, `/terms`, `/terms/`, `/support`, `/support/`
   without login: readable compatibility HTML with automatic browser navigation
   (or fallback links) to the canonical page. Check the
   admin root still reaches the admin SPA and event URLs retain their own routing.
4. Verify an installed Android link and the App Store metadata links after rollout.
   Local demo emulators and source checks are not production/device verification.

Local checks: `node --test scripts/public-legal-redirects.test.mjs`, relevant admin
build/typecheck and a **loopback-only Hosting emulator** under a `demo-*` project.
The regressions pin fixed HTTPS destinations, script-free navigation/fallbacks,
installed Android locale contracts, unchanged SPA/event rewrites and the
Hosting-only deployment path.

Outstanding operational follow-ups include actual provider agreements/transfers,
TTL activation, retention of evidence/quoted content and completion responses,
#1245's closed/unmerged consent change, storefront trader details and notifying
users of material policy changes. This PR does not certify those controls or
supply missing postal/account details. No backend, Firebase rules, app release,
Play setting or iOS code is changed by the routing correction.

Provider routing reference:
https://firebase.google.com/docs/hosting/full-config#hosting_priority_order

## Local verification — 2026-10-29

- Shared package build, admin typecheck and production Vite build passed. The build
  includes all three compatibility pages and no removed legal stylesheet.
- Admin lint passed with existing warnings; all 542 admin tests and all 52 repo
  script tests passed under the loopback/no-cloud guard.
- A demo-project Hosting emulator served all six route forms with status 200, the
  expected fixed meta-navigation destination and unchanged security headers. The
  root and a nested admin route still served the admin SPA.
- A real browser opened the emulated `/privacy` link and automatically navigated
  to the canonical HTTPS homepage. The four revised homepage pages were checked
  separately in a local browser; their new text is not yet deployed.
- The temporary emulator config used relative paths to the built output. An initial
  absolute-path harness configuration returned 404 for every route, including the
  admin root; correcting only that temporary config resolved the harness issue.
- No physical-device, post-merge production or storefront verification is claimed.
