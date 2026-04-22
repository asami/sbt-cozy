#!/bin/sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
BASE_REF=${BRIDGE_CHECK_BASE:-HEAD}
FIXTURE_PATH=bridge-fixtures/sbt-bridge

if ! git -C "$ROOT_DIR" rev-parse --verify "$BASE_REF" >/dev/null 2>&1; then
  echo "base ref not found: $BASE_REF" >&2
  exit 1
fi

tracked_changed=false
if ! git -C "$ROOT_DIR" diff --quiet "$BASE_REF" -- "$FIXTURE_PATH"; then
  tracked_changed=true
fi
untracked_changed=false
if git -C "$ROOT_DIR" status --short --untracked-files=all -- "$FIXTURE_PATH" | grep -q '^?? '; then
  untracked_changed=true
fi
if [ "$tracked_changed" = false ] && [ "$untracked_changed" = false ]; then
  echo "no bridge fixture changes detected"
  exit 0
fi

if git -C "$ROOT_DIR" diff --unified=0 "$BASE_REF" -- build.sbt | grep -Eq '^[+-]ThisBuild / version :='; then
  echo "bridge fixture change is accompanied by build.sbt version change"
else
  echo "bridge fixture files changed without a build.sbt version bump" >&2
  exit 1
fi
