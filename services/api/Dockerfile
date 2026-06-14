# syntax=docker/dockerfile:1
# ── Build stage ────────────────────────────────────────────────────────────────
FROM node:24-alpine AS builder
WORKDIR /app

# Copy workspace root manifests first for better layer caching.
COPY package.json package-lock.json ./
COPY packages/shared/package.json ./packages/shared/
COPY services/api/package.json ./services/api/
COPY apps/admin/package.json ./apps/admin/
COPY apps/mobile/package.json ./apps/mobile/

RUN npm ci -w @carcommunity/shared -w @carcommunity/api

# Copy source needed for build.
COPY packages/shared ./packages/shared
COPY services/api ./services/api

RUN npm run build -w @carcommunity/shared \
 && npm run prisma:generate -w @carcommunity/api \
 && npm run build -w @carcommunity/api

# ── Runtime stage ──────────────────────────────────────────────────────────────
FROM node:24-alpine AS runner

RUN addgroup -S nodejs \
 && adduser -S appuser -G nodejs

WORKDIR /app

# Copy workspace manifests and install production dependencies only.
COPY package.json package-lock.json ./
COPY packages/shared/package.json ./packages/shared/
COPY services/api/package.json ./services/api/
COPY apps/admin/package.json ./apps/admin/
COPY apps/mobile/package.json ./apps/mobile/

RUN npm ci --omit=dev

# Copy Prisma-generated client from builder (prisma CLI is a devDependency and
# is not reinstalled; the generated output must be carried over explicitly).
COPY --from=builder /app/node_modules/.prisma ./node_modules/.prisma

# Copy compiled output.
COPY --from=builder /app/packages/shared/dist ./packages/shared/dist
COPY --from=builder /app/services/api/dist ./services/api/dist

USER appuser

EXPOSE 4000

CMD ["node", "services/api/dist/index.js"]
