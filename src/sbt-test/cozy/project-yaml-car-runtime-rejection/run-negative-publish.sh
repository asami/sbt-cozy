#!/bin/sh
set -eu

plugin_version=$(cat target/plugin.version)
cd negative-project
exec sbt "-Dplugin.version=$plugin_version" --batch publish
