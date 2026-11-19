# Partner portal visual QA

final result: passed

Scope: the new KCC Admin application-review screen, using the production
ApplicationReview and AdminShell components in an explicitly isolated local QA
fixture. This is not visual acceptance of all native screens or the earlier
84-screen redesign. No production account, company or permission was changed.

## Target and evidence

- Source: `references/2026-11-17-partners/selected-concept.png`, the first displayed
  Product Design concept selected by the user. Original pixels: 1487 x 1058.
- Desktop: `evidence/2026-11-17-partners/admin-review-desktop.png`, browser viewport
  and screenshot 1440 x 1024, density 1.
- Combined full-view input: `evidence/2026-11-17-partners/admin-comparison.png`.
  The source is normalized to 1440 x 1024 for this comparison; the implementation
  is unchanged. The small source aspect-ratio difference is presentation-only.
- Mobile: `evidence/2026-11-17-partners/admin-review-mobile.png`, 390 x 844,
  density 1. DOM measurement confirms scrollWidth == viewport width == 390.
- Before fixes: `admin-review-before.png` and `admin-review-mobile-before.png`
  in the same evidence directory.
- URL: `/qa/partners.html`, separate Vite configuration with transport/auth mocks.
  It is not included in the production app entry or its Firebase build.
- State: Swedish, dark theme, submitted application with explicit fictional QA
  contact data and an existing local logo fixture. New applicants have no approved
  offers; the preview correctly shows the empty-offers state.

## Comparison and iterations

The selected hierarchy is preserved: review data on the left, public app preview
on the right, clear requested company permission, billboard request, and a gold
publish action. The existing production sidebar, tokens, typography, icons,
accessible dialogs and actual applicant data remain authoritative. We do not
invent the concept's fictional offers or merchant logo for a real applicant.

1. [P2, resolved] Desktop publish actions originally fell below the viewport.
   The action region is now sticky with a clear gold primary action. Redundant
   Back-link spacing was removed; breadcrumbs retain navigation. The final
   desktop capture verifies the fix.
2. [P2, resolved] At 390 px, the organisation-number label overlapped its value.
   Mobile definition rows now stack label/value, wrap long content and have
   readable row spacing. The final mobile capture verifies the fix.
3. [P2, resolved] The mobile footer originally occupied about 256 px. Secondary
   actions now share a row and the primary action has a full-width row; the
   compact footer preserves at least 44 px targets.
4. [P2, resolved] Category and map-fallback text had untranslated keys/English.
   The category uses Swedish localization. Location confirmation is in the
   publish dialog with Swedish fallback and manually required coordinates.

Focused inspection used the company-data and action regions of the full-size
desktop/mobile screenshots, alongside the corresponding areas of the combined
reference input. The 390 px view is a responsive adaptation; no mobile-admin
reference was supplied.

## Required fidelity surfaces

| Surface        | Result                                                                                                                                                 |
| -------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Typography     | Production system family/tokens; heading, body and supporting hierarchy legible; mobile labels no longer collide.                                      |
| Spacing/layout | Two-column desktop; single-column mobile; persistent primary action, no horizontal overflow at 390 px.                                                 |
| Colors/tokens  | Existing dark admin palette, gold primary with dark text, semantic status text. No invented palette.                                                   |
| Image quality  | Actual permitted local test asset rendered with contain; production uses server-validated partner logos. Concept merchant art is not fabricated in UI. |
| Copy/content   | Public preview excludes private contact name/email/telephone; empty offers remain honest; billboard placement is separately reviewed.                  |

## Interactions and limits

In the browser the publish dialog started with a disabled confirm button.
Manual latitude/longitude and an explicit reviewer confirmation enabled it.
Submitting changed only the in-memory fixture to Approved and showed the success
message. No browser-console errors were recorded. Adapter/DOM tests separately
cover transport payloads, version binding, duplicate submission, rejection,
complement requests, cursor filtering and failed mutation behavior.

The screenshot API intermittently failed at mobile width; the documented
getScreenshot API produced the reviewed and saved mobile evidence. No browser
fallback outside the selected in-app browser was used.

No actionable P0/P1/P2 issue remains in this scoped admin review. Native iOS
rendering, actual camera scanning, provisioned Mapbox logo rendering and real
production end-to-end interaction require device/integration verification before
release. Those limits are not presented as passing visual checks.

## Implementation checklist

- [x] Compare selected source and browser implementation in one image.
- [x] Correct desktop action visibility and mobile overlap.
- [x] Capture revised desktop and mobile states.
- [x] Exercise guarded publish-dialog behavior in an isolated fixture.
- [x] Check console errors and horizontal layout bounds.
- [x] Preserve private/public data separation and honest empty content.

Follow-up P3: broader light-theme, keyboard-only and long-content visual matrices.
