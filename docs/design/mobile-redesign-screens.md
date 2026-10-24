# Native redesign migration register

Source: 84 concept views in the user-supplied 2026-10-24 catalogue. All IDs are
design review IDs, not runtime route identifiers. This is a migration backlog,
not a statement that every view has been implemented or verified.

The first Android/iOS PR pair migrates tokens, shared surfaces, the map shell,
navigation, profile access to drives and the opaque chat/Driver host. Detailed
feature-specific layouts and states remain pending unless a platform report
contains explicit rendered evidence. Business behavior must remain reachable
through every step. Driver feed implementation is included now; paid cosmetics
are deferred by the user's scope choice.

| IDs | Catalogue page | Screens / states | Initial implementation lane |
| --- | --- | --- | --- |
| S01-S04 | 4 | Map home, expanded search, layers, place menu | Both native shells; visual comparison required |
| S05-S08 | 5 | Android route suggestions/navigation, saved places/save | Preserve native flows; Android tool-rail parity |
| S09-S12 | 6 | Report, incident detail, police information, sponsored pin | Preserve actions; inherited surface styling only |
| S13-S16 | 7 | Start drive, live controls, driver preview, convoy map | Shell styling; preserve session lifecycle |
| S17-S20 | 8 | Crown hub, curated point, spawn, reward | Preserve claims; tier/animation refinement pending |
| S21-S24 | 9 | Crown stats/history, season, rules, leaderboard | Inherited surfaces; detailed visual pass pending |
| S25-S28 | 10 | Perk shop/use/trap, KP ledger | Preserve server behavior; detailed pass pending |
| S29-S32 | 11 | Chat home, community, convoy list/chat | Both platforms: opaque Chat tab and existing channels |
| S33-S36 | 12 | Inbox, DM, new conversation, friends | Preserve rules/deeplinks; inherited surfaces |
| S37-S40 | 13 | Driver feed/detail, message actions, share location | Driver native reading now; original rules preserved |
| S41-S44 | 14 | Events list/detail/editor/chat | Preserve functionality; detailed visual pass pending |
| S45-S48 | 15 | Check-in, group drive, share event, Social | Preserve paid/free boundaries; Social shell updated |
| S49-S52 | 16 | Convoys, create/detail/ended | Preserve coordinator and sessions; detailed pass pending |
| S53-S56 | 17 | My drives, drive detail/stats, save/retry | Move entry to profile; preserve data and restoration |
| S57-S60 | 18 | Garage, vehicle/editor, crop | Direct Garage tab; inherited surfaces |
| S61-S64 | 19 | Profile/edit/member/badge wall | My drives entry + surface styling; detail pass pending |
| S65-S68 | 20 | Partners/offers/application/privacy | Preserve access/consent; detail pass pending |
| S69-S72 | 21 | Settings, inbox/preferences/blocked | Inherited surfaces; accessibility regression checks |
| S73-S76 | 22 | Feedback/tickets/deletion/What's new | Preserve recovery/account flows; detail pass pending |
| S77-S80 | 23 | Android login, onboarding/welcome/membership | Inherited tokens; real auth/billing checks pending |
| S81-S84 | 24 | iOS login, frame picker, restricted account, permission | Preserve OS/auth; S82 paid cosmetics deferred |

## Follow-up PR requirements

Name individual IDs and the actual states changed. Include same-size reference
and rendered captures, locale/theme/text-scale coverage, tested paths and
unverified configured-device scenarios. Refine this register after a delivery;
never mark an entire family complete because one screenshot or token changed.

The Android report lives at `docs/design/android-redesign-validation.md`; the
iOS report at `docs/design/ios-redesign-validation.md`. They are created with the
respective implementation PRs and should distinguish automated results,
rendered evidence, manual device checks and dependencies.
