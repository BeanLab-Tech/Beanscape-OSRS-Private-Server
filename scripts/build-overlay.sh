#!/usr/bin/env bash
# This repo only holds the BeanLab overlay (GE, name change, migrations, docs). Gradle, the engine
# and the rest of RsMod live upstream. This script checks out the pinned upstream commit, copies
# the overlay on top and runs Gradle there, so the overlay can be built and tested anywhere.
#
#   scripts/build-overlay.sh                      # BeanLab module tests + formatting check
#   scripts/build-overlay.sh build -x test        # any Gradle arguments
#   UPSTREAM_REF=<sha> scripts/build-overlay.sh   # try a different upstream commit
set -euo pipefail

UPSTREAM_URL="${UPSTREAM_URL:-https://github.com/rsmod/rsmod.git}"
# Latest upstream commit on rev 233 (2025-09-30); the commit the overlay is verified against.
UPSTREAM_REF="${UPSTREAM_REF:-fa13b3f67172ef36e15d6b1514358aee61411796}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${WORK:-$ROOT/build/upstream}"

if [[ ! -d "$WORK/.git" ]]; then
    mkdir -p "$WORK"
    git -C "$WORK" init -q
    git -C "$WORK" remote add origin "$UPSTREAM_URL"
fi
if [[ "$(git -C "$WORK" rev-parse -q --verify HEAD || true)" != "$UPSTREAM_REF" ]]; then
    git -C "$WORK" fetch -q --depth 1 origin "$UPSTREAM_REF"
    git -C "$WORK" checkout -q --force --detach FETCH_HEAD
fi
# Drop files from a previous overlay copy (keeping Gradle caches and build outputs), then re-apply.
git -C "$WORK" reset -q --hard
git -C "$WORK" clean -fdq -e .gradle -e build -e .kotlin
cp -R "$ROOT/api" "$ROOT/content" "$WORK/"

if [[ $# -eq 0 ]]; then
    set -- \
        :content:other:grandexchange:test \
        :content:other:grandexchange:spotlessCheck \
        :content:other:commands:compileKotlin \
        :content:other:commands:spotlessCheck
fi
cd "$WORK"
exec ./gradlew "$@"
