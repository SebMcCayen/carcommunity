# Native redesign migration register

Source: 84 concept views in the user-supplied 2026-10-24 catalogue. All IDs are
design review IDs, not runtime route identifiers. This is a migration backlog,
not a statement that every view has been implemented or verified.

The first Android/iOS PR pair migrated the shared foundation and shell. Android
wave 2 implements the feature areas below; this does not establish rendered
acceptance of every catalogue state. Business behavior must remain reachable.
Driver reading is included; paid cosmetics are deferred. Current iOS feature
work is owned separately on the Mac mini, not by this Android wave.

## Current Android migration — 2026-10-25

"Implemented" refers to source and regression coverage, not full visual/device
acceptance. Exact app SHAs, executed jobs and actual capture limits are in
[Android validation](android-redesign-validation.md) and
[executed evidence](redesign-executed-evidence.md).

| IDs / area       | Android source status                                                                                                                   | Rendered evidence / remaining scope                                                                                                                                                           |
| ---------------- | --------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| S01-S08          | Existing single-map shell/search/tool rail retained; detail sheets/places/search refined in #1178.                                      | Earlier shell captures; wave-2 place sheet. Real map/navigation and viewport variants still require configured QA.                                                                            |
| S09-S16, S49-S52 | Incident/police/sponsored sheets, live and convoy presentation implemented in #1178. Exit actions remain reachable before long rosters. | Live preview and place sheet; not complete police/incident/convoy/GPS-state coverage.                                                                                                         |
| S17-S28          | Crown summaries, stats/rewards/perks/trap, ledger and leaderboard refined in #1179; claim rules unchanged.                              | Crown hub preview; real claim/budget/DST and all detailed reward states remain.                                                                                                               |
| S29-S40, S44     | Chats, DM/friends/new chat, event chat and read-only Driver feed/detail implemented in #1180; per-account state preserved.              | Chat, populated local Driver feed/detail; keyboard, real accounts/moderation/block transitions and accessibility matrix remain. Unsupported Driver writes/linking destination not fabricated. |
| S41-S43, S45-S48 | Social dashboard/events/editor/share/group drive refined in #1179 and wired in #1181.                                                   | Event list/detail previews; editor/check-in/group-drive and full variants not visually accepted.                                                                                              |
| S53-S59, S61-S64 | Garage/profile/awards/drives implemented in merged #1176, with compact accessible garage menus in #1182.                                | Profile and final garage preview; other editor/statistics/retry/award states still need visual/device coverage.                                                                               |
| S60              | Existing crop mechanics retained.                                                                                                       | No new crop behavior or dedicated rendered acceptance claimed.                                                                                                                                |
| S65-S80          | Partners, settings, notifications/account/help/auth and membership refined in #1177.                                                    | Settings/membership previews; real auth, Play lifecycle, partner codes and remaining screen variants still require validation.                                                                |
| S81              | iOS login, out of current Android implementation scope.                                                                                 | Owned by iOS lane.                                                                                                                                                                            |
| S82              | Paid profile-frame purchases deliberately deferred.                                                                                     | No payment/catalogue/ownership implementation claimed.                                                                                                                                        |
| S83-S84          | Existing restricted-account and native permission guards retained.                                                                      | No invented standalone replacement screen; real account/OS transitions remain to test.                                                                                                        |

The twelve wave-2 captures are native production components with local test
inputs, English/dark/normal text at 320 × 640 pixels. They are not a full
84-view, Swedish/light/large-text or configured-device acceptance pass.

## Historical initial allocation — not the current Android to-do list

The initial joint allocation below is retained to explain the foundation split.
Its "inherited"/"pending" labels are historical; current Android status is above.
Do not infer today's iOS progress from this initial table.

| IDs     | Catalogue page | Screens / states                                           | Historical initial implementation lane                   |
| ------- | -------------- | ---------------------------------------------------------- | -------------------------------------------------------- |
| S01-S04 | 4              | Map home, expanded search, layers, place menu              | Both native shells; visual comparison required           |
| S05-S08 | 5              | Android route suggestions/navigation, saved places/save    | Preserve native flows; Android tool-rail parity          |
| S09-S12 | 6              | Report, incident detail, police information, sponsored pin | Preserve actions; inherited surface styling only         |
| S13-S16 | 7              | Start drive, live controls, driver preview, convoy map     | Shell styling; preserve session lifecycle                |
| S17-S20 | 8              | Crown hub, curated point, spawn, reward                    | Preserve claims; tier/animation refinement pending       |
| S21-S24 | 9              | Crown stats/history, season, rules, leaderboard            | Inherited surfaces; detailed visual pass pending         |
| S25-S28 | 10             | Perk shop/use/trap, KP ledger                              | Preserve server behavior; detailed pass pending          |
| S29-S32 | 11             | Chat home, community, convoy list/chat                     | Both platforms: opaque Chat tab and existing channels    |
| S33-S36 | 12             | Inbox, DM, new conversation, friends                       | Preserve rules/deeplinks; inherited surfaces             |
| S37-S40 | 13             | Driver feed/detail, message actions, share location        | Driver native reading now; original rules preserved      |
| S41-S44 | 14             | Events list/detail/editor/chat                             | Preserve functionality; detailed visual pass pending     |
| S45-S48 | 15             | Check-in, group drive, share event, Social                 | Preserve paid/free boundaries; Social shell updated      |
| S49-S52 | 16             | Convoys, create/detail/ended                               | Preserve coordinator and sessions; detailed pass pending |
| S53-S56 | 17             | My drives, drive detail/stats, save/retry                  | Move entry to profile; preserve data and restoration     |
| S57-S60 | 18             | Garage, vehicle/editor, crop                               | Direct Garage tab; inherited surfaces                    |
| S61-S64 | 19             | Profile/edit/member/badge wall                             | My drives entry + surface styling; detail pass pending   |
| S65-S68 | 20             | Partners/offers/application/privacy                        | Preserve access/consent; detail pass pending             |
| S69-S72 | 21             | Settings, inbox/preferences/blocked                        | Inherited surfaces; accessibility regression checks      |
| S73-S76 | 22             | Feedback/tickets/deletion/What's new                       | Preserve recovery/account flows; detail pass pending     |
| S77-S80 | 23             | Android login, onboarding/welcome/membership               | Inherited tokens; real auth/billing checks pending       |
| S81-S84 | 24             | iOS login, frame picker, restricted account, permission    | Preserve OS/auth; S82 paid cosmetics deferred            |

## Follow-up PR requirements

Name individual IDs and the actual states changed. Include same-size reference
and rendered captures, locale/theme/text-scale coverage, tested paths and
unverified configured-device scenarios. Refine this register after a delivery;
never mark an entire family complete because one screenshot or token changed.

The Android report lives at `docs/design/android-redesign-validation.md`; the
iOS report at `docs/design/ios-redesign-validation.md`. They are created with the
respective implementation PRs and should distinguish automated results,
rendered evidence, manual device checks and dependencies.
