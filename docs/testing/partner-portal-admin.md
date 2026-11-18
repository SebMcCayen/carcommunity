# Partner portal: KCC Admin validation

The admin implementation extends the existing React/Vite portal and its design
tokens. It uses the selected review layout with business details and permissions
on the left, an embedded public-profile preview on the right, and a persistent
action footer. The application preview uses submitted public fields only; the
private contact person, contact email and contact phone remain outside it.

## Implemented paths

- `/partners/applications`: server-filtered cursor pagination, application review,
  explicit approve-and-publish, request changes and rejection. Publication requires
  a chosen real location, an explicit verification checkbox, and the revision that
  was actually reviewed. Self-service binds the applicant account on the server.
- `/partners`: cursor-paginated business directory and manual draft creation.
- `/partners/:partnerId`: profile editing, logo upload with a usage-rights
  declaration, company-scoped manager/redeemer grants and revocation, and explicit
  lifecycle actions. Name/location changes require draft or paused status.
- `/partners/offers`: server-filtered cursor pagination, personal-QR campaign
  creation with optional capacity and reservation validity, full terms review,
  and publication bound to the reviewed campaign version. Existing shared-code
  offer management is preserved. Dialogs use the existing native `ActionDialog`
  for focus management, Escape handling and focus restoration.
- `/partners/statistics`: delayed aggregate redemption counts for all businesses,
  one business or one offer. Filters offer all-time and closed UTC months. No raw
  customer identifiers, unique-person metric or individual redemption times are
  requested or displayed.
- `/billboards`: cursor pagination, business filtering from profile/review links,
  partner-profile navigation targets and copying the validated company logo onto
  a draft or paused billboard. Placement approval remains separate.

## Automated checks

Validated locally on 17 November 2026:

```sh
npm run typecheck --workspace=@carcommunity/admin
npm run lint --workspace=@carcommunity/admin
npm run build --workspace=@carcommunity/admin
npm test --workspace=@carcommunity/admin
```

All commands passed. The complete admin suite passed **51 files / 565 tests**.
Lint reported zero errors and existing unused legacy parameter warnings. The
production build retains the existing large Mapbox bundle warning. Swedish and
English keys added for this feature were checked for matching key sets and
missing literal UI keys.

The new regression coverage verifies:

- filters run before cursor boundaries; an extra document detects another page,
  and an exact final 20-document page does not advertise an extra page;
- private application contacts are excluded from the embedded public preview;
- publication requires deliberate confirmation, carries the reviewed revision,
  rejects duplicate in-flight submissions and preserves a rejected operation for
  correction;
- request-changes sends the concrete reviewer message;
- membership changes carry a single company scope and no administrator claim;
- logo upload records the explicit usage-rights declaration;
- the personal-QR form creates a three-place campaign through the managed API;
- full offer terms appear before activation, and activation includes the reviewed
  version;
- statistics use aggregate endpoints and offer only completed monthly periods.

These tests mock the transport and do not claim production Firebase writes or a
real administrator login. Backend authorization, concurrent quota and redemption
validation belong to the companion backend/emulator test evidence.

## Visual verification and deliberate differences

The isolated harness is documented in `apps/admin/qa/README.md` and starts with:

```sh
cd apps/admin
npx vite --config qa/vite.partners.config.ts
```

It renders the production `ApplicationReview`, `AdminShell`, localization and
styles at `/qa/partners.html`, using a clearly labelled fixture transport that
never connects to Firebase. The normal production entrypoint and authentication
are unchanged by the harness configuration.

The desktop comparison at 1440 × 1024 identified and corrected a hidden primary
action and two localization gaps. The review footer now keeps the publication
button visible while the review content scrolls. Location confirmation appears
inside the publication dialog, with localized manual-coordinate fallback.
The task's accompanying design QA record contains the final desktop, dialog and
390-pixel responsive evidence.

Intentional differences from the concept image are the existing KCC Admin
sidebar, a visibly labelled test business/logo, real form fields and no invented
offer cards before any offer has been submitted and approved. No production
profile, role, offer, billboard or customer record was changed for visual QA.
