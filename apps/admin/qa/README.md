# Partner review visual QA

Run from `apps/admin`:

```sh
npx vite --config qa/vite.partners.config.ts
```

Open `/qa/partners.html` on the displayed local server (default port 4317).
This uses the production `ApplicationReview`, `AdminShell`, design tokens and
localization with an explicitly labelled, isolated test fixture. The separate
Vite config replaces only the fixture transport and auth presentation. It never
connects to Firebase, grants an actual role, publishes an actual business, or
changes production authentication. Fixture decisions affect memory only.

The normal `vite.config.ts` continues to build `index.html`; no QA route or
fixture transport is included in that production entrypoint. Vitest checks the
real adapters and review transitions separately in `partner-portal.test.ts` and
`partner-application-review.test.tsx`.
