---
applyTo: 'apps/android/**,apps/ios/**,contracts/design-tokens/**,contracts/localization/**'
---

# Native mobile design changes

Read `docs/design/mobile-redesign.md` and its screen migration register before
changing mobile presentation. They capture the approved 2026-10-24 PDF direction.
Use semantic tokens from `contracts/design-tokens/tokens.json`; regenerate both
native outputs and both locales, never hand-edit generated resources.

Preserve native platforms, backend authority, existing feature flags and
capabilities. The main navigation is Map, Chat, Create/Stop, Social, Garage;
legacy History restores to My drives under Profile. The map stays composed once
and pauses rendering behind opaque Chat. Search starts collapsed; no logo or
duplicate Chat button in map chrome. Driver posts use original Driver API/access
rules, never fake content or a parallel profile. Cosmetic purchases are separate.

For every PR, identify affected screen IDs, state/accessibility variants,
Android/iOS implications, and actual test/visual evidence. Mark pending work
honestly; token adoption or a green build does not establish complete 84-screen
visual fidelity or configured-device correctness.
