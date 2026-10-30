# Compact crown map markers

Android follow-up to S01/S18/S19, 2026-10-26. This supersedes the transparent,
multiply outlined ring treatment for the map crown component. It does not change
Crown Hunt rewards, claim eligibility, limits, flags or backend collections.

## Appearance

Use an opaque rounded-square tile, one fine accent border and ONE draw of
the existing licensed crown vector. Night maps retain the navy surface; day maps
use the light distant palette below. Do not trace the crown repeatedly, add
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

The existing night/ready colours come from `contracts/design-tokens/tokens.json`;
generate native outputs rather than editing them by hand.

### Android day-map distant palette — approved 2026-10-30

The selected “Ljusa värdefärger” colour proposal changes only distant/unknown-position
crowns on a day map. Keep the licensed vectors, tile size, corner radius, stroke
width, rank bars and animation unchanged. The map's effective `MapMode` controls
this palette, including manual map overrides; do not read the phone theme directly.

| Value band | Distant day fill | Crown, border and bars | Foreground/fill contrast |
| ---------- | ---------------- | ---------------------- | -----------------------: |
| Common     | `#ECE9E3`        | `#5B6168`              |                    5.17:1 |
| Uncommon   | `#E6F3EB`        | `#1B6E4B`              |                    5.44:1 |
| Rare       | `#EEE7FA`        | `#6640A0`              |                    6.22:1 |
| Legendary  | `#FFF0CC`        | `#946000`              |                    4.73:1 |

These paired colours are local Android map-component values in `CrownMarkerBitmaps`;
the global generated palette, detail/list colours and iOS files are unchanged.
Curated points use the same reward-band mapping already described above. Night,
ready and legacy collected-badge pixels retain their previous colours. In range,
the stronger solid accent fill remains the proximity cue in both map modes.

A day/night change wakes the existing single crown render effect once, preserves
its animator/annotation state and swaps the cached appearance of settled crowns.
The image key and bitmap use the same per-frame mode snapshot. Only distant day
images need a separate key; identical ready/night/collected images remain reusable.
This avoids stale dark icons after switching the map to day mode without adding
an idle frame loop or replaying spawn animations.

Legacy disc/glow inputs are state adapters only, not instructions to paint the
old incident-style disc or a halo. All appearance metadata participates in the
style-image cache key. No bitmap generation happens in the animation frame loop.

## State and motion

- Distant/unknown position: light paired colours on day maps, navy tile with bright
  accent glyph, border and rank bars on night maps.
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
