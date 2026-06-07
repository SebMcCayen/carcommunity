# Security Policy

## Reporting a vulnerability

If you discover a security vulnerability, report it privately to the maintainers.

- **Do not open public GitHub Issues** for security vulnerabilities.
- Use private disclosure through repository maintainers.
- Security contact: **security contact to be added**.

When reporting:

- Include clear reproduction details.
- Share impact and affected components.
- Keep reports minimal and factual.

## Sensitive data handling

- Do not include secrets in issues, pull requests, logs, screenshots, or crash dumps.
- Never share tokens, credentials, private keys, connection strings, or personal sensitive data.

## Supported branch

Security updates are supported on:

- `main`

## Security principles

- Backend is the source of truth for security and access decisions.
- No secrets in the mobile app.
- No secrets in the repository.
- Use GitHub Secrets and Azure secrets only for CI/CD and runtime secret handling.
- App error reporting to GitHub Issues must be sanitized before publication.