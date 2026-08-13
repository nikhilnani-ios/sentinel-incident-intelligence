FROM node:20-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm ci --no-audit --no-fund || npm install --no-audit --no-fund

FROM node:20-alpine AS build
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
# Next.js serialises rewrites at build time. Container images therefore need cluster-internal
# destinations while a host-side `npm run dev` continues to use next.config.mjs's localhost defaults.
ARG INCIDENT_API_URL=http://incident-service:8080
ARG INSIGHT_API_URL=http://insight-service:8080
ARG NEXT_PUBLIC_INCIDENT_API_URL=http://localhost:8083
ENV NEXT_TELEMETRY_DISABLED=1 \
    NEXT_PUBLIC_INCIDENT_API_URL=${NEXT_PUBLIC_INCIDENT_API_URL} \
    INCIDENT_API_URL=${INCIDENT_API_URL} \
    INSIGHT_API_URL=${INSIGHT_API_URL}
RUN npm run build

FROM node:20-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1

RUN addgroup -g 10001 sentinel && adduser -u 10001 -G sentinel -D -s /sbin/nologin sentinel

# `output: standalone` emits only the files the server actually needs — a ~40MB image instead of
# shipping node_modules.
COPY --from=build --chown=sentinel:sentinel /app/.next/standalone ./
COPY --from=build --chown=sentinel:sentinel /app/.next/static ./.next/static
COPY --from=build --chown=sentinel:sentinel /app/public ./public

USER sentinel
EXPOSE 3000
CMD ["node", "server.js"]
