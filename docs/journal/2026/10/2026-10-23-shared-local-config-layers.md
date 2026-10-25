# Align shared project defaults with Cozy's existing conf/cozy location

## Decision and scope

The final user decision is to use the existing `conf/cozy/config.yaml` shared
configuration and overlay `.cozy/config.yaml` locally. Root `config.yaml` is not
loaded. This avoids introducing another placement convention.

The prior adapter read only `.cozy/config.yaml`. It now adds
`cozySharedConfigFile` for the shared file and `cozyConfigSources` for ordered
source diagnostics. The existing `cozyConfigFile` remains the local selector,
including custom selections. `cozyProjectConfig` merges the selected files and
feeds existing generation/publication/warehouse settings.

See [the specification](../../../spec/project-configuration-layers.md).

## Merge behavior

Local values win; unspecified shared siblings survive. Lists replace as a
whole, and explicit `[]` clears a list. Authored empty identity values retain
their existing validation meaning. Parsed map markers are not mistaken for
leaf replacements, so local nested options preserve unspecified shared options.

The delegated-command diagnostic now describes Cozy configuration rather than
assuming that the setting necessarily came from a local file.

## Validation and preservation

The final command was:

```text
testOnly org.goldenport.cozy.CozyProjectConfigLayersSpec org.goldenport.cozy.CozyProjectConfigSpec org.goldenport.cozy.CozyDelegatedGeneratorSpec -- -oD
```

Compilation succeeded and all 50 tests across three suites passed. SBT and
wrapper exits were 0; terminal process/native-locking evidence was verified.
The new tests evaluate the actual production SBT setting graph, including
shared-only, local-only, both, neither, ignored root YAML and custom local-file
selection. Additional tests exercise list and map overlays and empty identities.

Final evidence (repository-relative):

- `.codex-workflow/disposable/a-b19668e4-f948-4388-adbf-0fda0b9859df/command-result.json`
- `.codex-workflow/disposable/a-0939b8ef-021b-4e04-8678-f1eb20257a35/57058-20261022T212555Z.summary.json`

Earlier candidates each passed 49 tests; their results remain retained. During
final preparation the runner initially omitted a complete renderer output from
its local accumulator. The original full response remained available. It was
parsed directly, checked, and submitted unchanged without rerendering or
repeating SBT execution. This preparation error is not a product test failure.

Preexisting installed-runtime and local-application-publication source/tests,
workflow state and evidence were preserved. Their code was compiled as part of
the repository, but the focused selection does not claim full runtime acceptance.

## Handoff

Use `conf/cozy/config.yaml` for shared project defaults and `.cozy/config.yaml`
for local overrides. Inspect sources with `show cozyConfigSources`.
Cozy retains its existing placement/discovery rules and now preserves shared
structured settings during overlay. No sm-workflow config migration, untracking,
installed-runtime switch, artifact publication, commit or push occurred here.
Any later consumer migration should use these existing locations after the
updated producer artifacts are deliberately made available.
