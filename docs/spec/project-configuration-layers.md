# Shared and local project configuration

The shared configuration is `conf/cozy/config.yaml`, matching Cozy's existing
configuration directory. `.cozy/config.yaml` overlays it as local configuration.
Root `config.yaml` is not read. This change does not migrate consumer files,
untrack local settings, or publish/switch an installed runtime.

## Settings and precedence

- `cozySharedConfigFile`: defaults to `baseDirectory / "conf" / "cozy" / "config.yaml"`.
- `cozyConfigFile`: retains its local default `baseDirectory / ".cozy" / "config.yaml"`.
- `cozyConfigSources`: existing selected files, shared first and local last.
- `cozyProjectConfig`: merged defaults used by existing adapter settings.

Either file can be absent. Existing custom `cozyConfigFile` selections remain
higher priority. `show cozyConfigSources` reports the loading order without
printing potentially private configuration values. Explicit SBT settings retain
their normal override behavior.

The existing supported YAML subset remains the parser contract. Local leaf
values replace matching shared values; maps retain unspecified shared siblings,
including maps that retain an authored empty marker. Local lists replace the
whole shared list; `[]` explicitly clears it. Authored empty identity overrides
remain empty so existing validation can diagnose them.

Cozy itself retains its user/conf/local multi-format layers. This adapter change
does not add user-level or other-format loading to sbt-cozy. `project.yaml`
remains artifact metadata, independent of operation defaults.

## Verification

The production SBT setting graph is evaluated in focused tests for both files,
either file alone, neither file, custom local selection, and ignored root YAML.
Additional tests cover whole-list replacement, empty lists, map inheritance and
empty identity overrides. Existing parser and delegated-generator tests provide
regression coverage. No test publication or sm-workflow migration is required.
