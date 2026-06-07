# Contributing to carcommunity

Thank you for contributing to `carcommunity`.

## Pull requests

- Use pull requests for all changes.
- Keep each PR small, focused, and easy to review.
- Link related issues when relevant.

## Source of truth

- Follow `.github/copilot-instructions.md`.
- Follow product and technical decisions in `docs/`.
- Prefer minimal, safe, production-ready changes.

## Language and branding rules

- Write code and technical comments in English.
- MVP user-facing text should be Swedish and implemented through i18n keys.
- Do not hardcode `KCC` or `Kungsbacka Car Community` where brand config/i18n should be used.

## Dependencies and security

- Do not add unnecessary dependencies.
- Do not commit secrets, keys, credentials, or real `.env` files.
- Use `.env.example` for placeholders only.

## Validation before PR

Run the repository checks before opening or updating a PR:

```bash
npm run lint
npm run typecheck
npm test
npm run build
```

Current note: these root scripts are placeholder checks and currently only print that no lint/typecheck/test/build tasks are configured yet.

## Issue labels (quick guide)

Use clear labels to speed up triage, for example:

- `bug`: broken or incorrect behavior
- `enhancement`: improvement to existing behavior
- `feature`: new capability request
- `documentation`: docs-only updates
- `good first issue`: beginner-friendly tasks
- `help wanted`: needs contributor support