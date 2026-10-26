# Compact crown map markers

Android follow-up to S01/S18/S19, 2026-10-26. This supersedes the transparent,
multiply outlined ring treatment for the map crown component. It does not change
Crown Hunt rewards, claim eligibility, limits, flags or backend collections.

## Appearance

Use an opaque navy rounded-square tile, one fine accent border and ONE draw of
the existing licensed crown vector. Do not trace the crown repeatedly, add
concentric rings, blur/glow or run idle animations. These details previously
merged into a smudge on a busy map. Crowns remain below incidents/navigation.

| Spawn tier | Reference base KP | Accent token                 | Tile side | Rank bars |
| ---------- | ----------------: | ---------------------------- | --------: | --------: |
| Common     |                10 | `crownCommon` (silver)       |     26 dp |         1 |
| Uncommon   |                25 | `crownUncommon` (mint green) |     28 dp |         2 |
| Rare       |               100 | `crownRare` (lavender)       |     30 dp |         3 |
| Legendary  |               500 | `crownGold`                  |     32 dp |         4 |

There is 1 dp transparent anti-alias padding on each side. Small size differences,
existing distinct silhouettes and the 1–4 bars preserve hierarchy without relying
on colour. Exact KP is displayed by the existing selected detail popup from the
server data, never tiny map text or a speculative reward. Curated points have no
spawn rarity: their actual reward chooses the corresponding visual value band;
this must not be described as a new rarity or change their reward policy.

Colours come from `contracts/design-tokens/tokens.json`; generate native outputs.
The navy reading surface and bright glyph/border work on light and dark maps.
Legacy disc/glow inputs are state adapters only, not instructions to paint the
old incident-style disc or a halo. All appearance metadata participates in the
style-image cache key. No bitmap generation happens in the animation frame loop.

## State and motion

- Distant/unknown position: navy tile with accent glyph, border and rank bars.
  Missing position must never look ready. Preserve the existing range calculation.
- Within geographic collection radius: solid accent fill with navy glyph/bars
  and one light border. No perpetual pulse across dozens of crowns. This indicates
  proximity only: GPS, stillness, limits and account checks still gate collection
  in the popup and on the server.
- Sending, transport failure or refused claim: retain the crown. Do not invent
  an earned reward or animate a successful collection before server confirmation.
- Confirmed collection: remove the member's crown from the incoming render set
  immediately; retain the last image only for the 320 ms shrink/fade exit and then
  delete its annotation. Keep the claim result readable. No spinning exit or
  repeating celebration on duplicate responses.
- Respect Android's disabled system animations: reconcile to the final state
  immediately. Otherwise short appearances are staggered at 45 ms, capped at six
  steps; there is no frame loop once the layer settles.

Shared common/uncommon crowns remain live for other members. The member's
existing UID-scoped collector cache/server reconciliation suppresses their own
already-collected spawns on refresh/restart; do not delete shared data to achieve
visual removal. Curated point suppression must respect its separate claim model;
it lasts until next UTC midnight or map composition disposal/account switch.
Once/weekly points can therefore reappear before they are eligible again; the
server still decides. No permanent admin-point eligibility is inferred locally.

## Platform and verification scope

Android rendering/host changes are in this PR. iOS implementation is being owned
separately on the user's Mac; generated Swift palette companions and this shared
specification are the handoff, not a claim of iOS behavioural parity or testing.

Verify bitmap size/corners/range/rarity on the native renderer, collection success
versus failures/refresh/user isolation, animation timing and reduced motion.
A labelled native component sheet is visual evidence, not a populated live-map
or physical-GPS acceptance test. Phone verification still needs real dense map
content, normal/large text detail popups, range transitions, a confirmed claim,
refresh/restart and system animations disabled. Do not publish or bump the app
version as a side effect of this component change.

Verified component evidence and test scope: [2026-10-26 crown tiles](evidence/2026-10-26-crown-tiles/README.md).
