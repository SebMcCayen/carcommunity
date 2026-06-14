# Deployment Readiness

This document describes the planned production deployment setup for the car community platform.

## Target Platform

**Azure Container Apps** — Production only. No staging or development cloud environments are planned for MVP.

## Service Overview

| Service | Container | Hosting |
|---------|-----------|---------|
| API (`services/api`) | Docker | Azure Container Apps |
| Admin (`apps/admin`) | Docker | Azure Container Apps |
| Mobile (`apps/mobile`) | — | App Store (iOS) and Google Play (Android) |

The mobile app is a React Native/Expo app distributed through the platform app stores. It is **not containerised**.

## Containers

- API and admin are **separate containers**, each with its own Dockerfile and port.
- API: `services/api/Dockerfile`, default port `4000`
- Admin: `apps/admin/Dockerfile`, default port `3000`
- Both Dockerfiles use multi-stage builds with a non-root runtime user.
- The admin image uses Next.js `output: 'standalone'` for a minimal runtime footprint.

## Health and Readiness Endpoints

| App | Endpoint | Purpose |
|-----|----------|---------|
| API | `GET /healthz` | Liveness — confirms the process is alive |
| API | `GET /readyz` | Readiness — ready for future dependency checks |
| Admin | `GET /healthz` | Liveness — suitable for container readiness probes |

## Security

- **No secrets are stored in this repository.**
- `.env` files are excluded from Docker images via `.dockerignore`. `.env.example` is tracked only as a template.
- Secrets (database connection strings, auth credentials, API keys) must be provided through Azure Container Apps environment variables or Azure Key Vault at deployment time.
- GitHub Actions secrets are required for any future deployment automation (e.g. ACR push, Container Apps deploy).
- Containers run as non-root users (`appuser`).
- No credentials appear in Dockerfiles or CI configuration.

## Production-Only Note

MVP infrastructure targets a single **Production** environment in Azure. Separate staging, preview, or development cloud environments are not planned at this stage. Keep changes conservative and production-safe.

## CI Container Build Validation

The `container-build` CI job builds both containers on every push/PR to `main` to catch Dockerfile issues early. Images are **not pushed** to any registry.

## Future Steps

The following steps are required before live production deployment and are **not yet configured**:

1. **Azure Container Registry (ACR)** — Create an ACR instance to store versioned container images.
2. **Azure Container Apps environment** — Create a Container Apps environment and individual Container Apps for API and admin.
3. **Managed Identity** — Use Azure Managed Identity for the Container Apps to authenticate with ACR and other Azure services without storing credentials.
4. **Database** — Provision an Azure Database for PostgreSQL Flexible Server and provide `DATABASE_URL` as a secret environment variable in Container Apps.
5. **GitHub Actions deployment workflow** — Add a workflow to build images, push to ACR, and deploy to Container Apps (requires repository secrets for Azure credentials).
6. **Custom domains and HTTPS** — Configure custom domains and managed TLS certificates in Azure Container Apps.
7. **Logs and monitoring** — Integrate with Azure Monitor and Log Analytics for container logs, metrics, and alerts.
8. **Database backups** — Configure automated point-in-time backups for the PostgreSQL database.
