#!/bin/sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
"$ROOT_DIR/scripts/check-bridge-fixtures.sh"
"$ROOT_DIR/scripts/check-version-bump.sh"
cd "$ROOT_DIR"
sbt --batch test
