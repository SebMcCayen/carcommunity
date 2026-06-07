# carcommunity

carcommunity is an open source monorepo for a safety-focused car community platform.

The MVP brand is **Kungsbacka Car Community (KCC)**.  
The codebase is intentionally **brand-ready** so it can support a future national name or multiple local communities without hardcoding KCC-specific behavior into core architecture.

## Open source status

- This repository is open source.
- Product and technical decisions are documented in `docs/` and `.github/copilot-instructions.md`.
- Never commit secrets to this repository.

## High-level architecture

- React Native / Expo mobile app
- Admin web app
- Node.js LTS backend API
- PostgreSQL database
- Mapbox integration
- GitHub Actions CI workflows

## Repository structure

```text
.
├── apps/
│   ├── mobile/         # React Native / Expo app
│   └── admin/          # Admin web app
├── services/
│   └── api/            # Node.js LTS backend API
├── docs/               # Product, architecture, security, data and design docs
└── .github/            # CI workflows and Copilot instructions
```

## Local development (placeholder)

Local setup and runtime instructions will be expanded as implementation code is added.

Minimum prerequisites:

- Node.js `>=24` (LTS line for this repository)
- npm (current stable release)

Current root workspace checks:

```bash
npm install
npm run lint
npm run typecheck
npm test
npm run build
```

## Security note

- No secrets in repository files or git history.
- Use `.env.example` as template only.

## Documentation

- [Product decisions](docs/product-decisions.md)
- [Architecture](docs/architecture.md)
- [Security](docs/security.md)
- [Data model](docs/data-model.md)
- [API guidelines](docs/api-guidelines.md)
- [Design system](docs/design-system.md)

## License and brand assets

- Source code is licensed under the [MIT License](LICENSE).
- Kungsbacka Car Community names, logos, crown mark, and brand assets are **not** covered by the MIT License and may not be used without permission.
