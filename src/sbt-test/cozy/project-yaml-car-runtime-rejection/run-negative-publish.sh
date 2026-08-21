#!/bin/sh
set -eu

child=${1:?child project is required}
plugin_version=$(cat target/plugin.version)
if [ "$child" = "negative-project-dir-env" ]; then
  export SBT_COZY_PROJECT_DIR="$PWD/local-cozy-project"
fi
cd "$child"
exec sbt "-Dplugin.version=$plugin_version" --batch publish
