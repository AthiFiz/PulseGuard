# PulseGuard frontend — production-style static build served by nginx.
#
# The Vite dev server is not used here and is not present in the final image.
# `npm run dev` remains the normal way to work on the frontend; this image is
# what a deployed PulseGuard looks like.

# ---------------------------------------------------------------------------
# Build stage — Node, lockfile-exact install, static output
# ---------------------------------------------------------------------------
FROM node:22-alpine AS build

WORKDIR /build

# Manifest and lockfile first: dependencies change far less often than source,
# so an ordinary code edit reuses this install.
COPY package.json package-lock.json ./

# `npm ci` rather than `npm install` — it installs exactly what the committed
# lockfile pins and fails if the two have drifted, instead of quietly resolving
# something newer at image-build time.
RUN npm ci

COPY . .

# Empty means same-origin: the bundle then requests "/api/v1/..." with no host,
# and nginx proxies it to the Control API. Baking "http://control-api:8080" in
# here would produce a bundle that only works inside the Compose network, which
# is exactly where the browser is not.
#
# For a non-Docker build this variable is left at its usual
# http://localhost:8080 (see .env.example), and nothing about that changes.
ARG VITE_API_BASE_URL=""
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL

# `tsc -b && vite build` — a type error fails the image build rather than
# shipping a broken bundle.
RUN npm run build

# ---------------------------------------------------------------------------
# Runtime stage — nginx and static files only
# ---------------------------------------------------------------------------
FROM nginx:1.29-alpine AS runtime

# The published nginx:1.29-alpine tag is rebuilt far less often than Alpine
# patches its packages, so a freshly pulled base still ships known-vulnerable
# openssl, curl, expat, libxml2 and nghttp2. Upgrading here picks up those fixes
# at build time — `--pull` alone does not, because the tag itself is unchanged.
#
# The cost is that the exact package set depends on when the image was built.
# That is the accepted trade: pinning versions instead would freeze this image
# on today's CVEs the moment Alpine moves on.
RUN apk upgrade --no-cache

# Replaces the image's default server block, which has no SPA fallback and no
# knowledge of the Control API.
COPY nginx/default.conf /etc/nginx/conf.d/default.conf

# Only the compiled output crosses the stage boundary: no node_modules, no
# TypeScript compiler, no Vite, no source.
COPY --from=build /build/dist /usr/share/nginx/html

EXPOSE 80

# The base image's own entrypoint and CMD start nginx in the foreground.
