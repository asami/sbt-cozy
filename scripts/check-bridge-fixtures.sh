#!/bin/sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
COZY_REPO_DIR=${COZY_REPO_DIR:-/Users/asami/src/dev2025/cozy}
SOURCE_DIR="$COZY_REPO_DIR/bridge/sbt-bridge/v1"
TARGET_DIR="$ROOT_DIR/bridge-fixtures/sbt-bridge/v1"

if [ ! -d "$SOURCE_DIR" ]; then
  echo "missing cozy bridge source directory: $SOURCE_DIR" >&2
  exit 1
fi
if [ ! -d "$TARGET_DIR" ]; then
  echo "missing sbt-cozy bridge fixture directory: $TARGET_DIR" >&2
  exit 1
fi

if diff -ru "$SOURCE_DIR" "$TARGET_DIR"; then
  echo "bridge fixtures are in sync"
else
  echo "bridge fixtures drifted from cozy source of truth" >&2
  exit 1
fi
