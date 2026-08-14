# PulseGuard CI — a Jenkins controller with just enough tooling to run the
# repository's own test commands.
#
# What this image deliberately does NOT contain: the Docker CLI, kubectl, the
# AWS CLI, Helm or Terraform. Stage 14 is continuous *integration* — it builds
# and tests the source and stops there. Nothing is packaged, pushed or
# deployed, so none of that tooling has a reason to exist here, and leaving it
# out keeps the container unable to do those things at all.

FROM jenkins/jenkins:2.568.2-lts-jdk21

# ---------------------------------------------------------------------------
# Node, for the frontend checks
# ---------------------------------------------------------------------------
# Java 21 arrives with the base image and Maven comes from each service's own
# ./mvnw wrapper, so Node is the only language runtime that has to be added.
#
# Installed from the official tarball at a pinned version rather than through
# an apt repository: one fewer signing key and package source to trust, and the
# version is exactly what is written here rather than whatever the repo's
# "22.x" points at today.
USER root

ARG NODE_VERSION=22.23.2

RUN set -eux; \
    arch="$(dpkg --print-architecture)"; \
    case "$arch" in \
        amd64) node_arch='x64' ;; \
        arm64) node_arch='arm64' ;; \
        *) echo "unsupported architecture: $arch" >&2; exit 1 ;; \
    esac; \
    # The .tar.gz rather than the smaller .tar.xz: this base image has no xz
    # binary, and pulling in xz-utils just to save a few megabytes of download
    # would add a package to the image for no lasting benefit.
    curl -fsSL "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-${node_arch}.tar.gz" \
        -o /tmp/node.tar.gz; \
    tar -xzf /tmp/node.tar.gz -C /usr/local --strip-components=1 \
        --exclude=CHANGELOG.md --exclude=LICENSE --exclude=README.md; \
    rm /tmp/node.tar.gz; \
    # Fail the image build now rather than the pipeline later.
    node --version; \
    npm --version

USER jenkins

# ---------------------------------------------------------------------------
# Git ownership
# ---------------------------------------------------------------------------
# When the host repository is bind-mounted for local builds, its files carry
# the host user's ownership while Jenkins runs as uid 1000. Git refuses to
# operate on a repository it considers to belong to somebody else, which shows
# up as "detected dubious ownership". That check protects a shared multi-user
# machine; this is a single-purpose local CI container, so it is switched off
# rather than worked around per-repository.
RUN git config --global --add safe.directory '*'

# ---------------------------------------------------------------------------
# Plugins
# ---------------------------------------------------------------------------
# A short, pinned list — see plugins.txt for what each one is for. The setup
# wizard is left enabled so the first admin user is created interactively and
# no credentials end up in this repository.
COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt
