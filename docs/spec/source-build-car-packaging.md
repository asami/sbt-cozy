# Explicit Snapshot source-build CAR packaging

`cozyCncfRuntimeArtifactInput` is an optional public `TaskKey[Option[File]]`.
Its default is `None`: ordinary resolved dependency JARs and package arguments
remain unchanged. A source-enabled owning build may explicitly select
`Some((source / Compile / packageBin).value)`, retaining descriptor preparation
in that source project's normal packaging task.

Selection requires an existing regular `.jar` and Snapshot component, CNCF,
and Cozy coordinates. The CNCF/Cozy pair comes from the effective
`CozyGenerationVersionContract.projectVersions` metadata. Invalid selected
inputs fail without falling back to published dependencies. The selected JAR
joins resolved library inputs; dependency inclusion and SPI policies still apply.

`CozySbtBridge.packageCar` accepts a trailing
`sourceBuildVersions: Option[(String, String)] = None`. A selected pair emits
`--source-cncf-version` and `--source-cozy-version` together. With `None` both
arguments are omitted. Project-root, provenance, release-source, and other
packaging arguments retain their existing meaning.

Cozy projects the explicit pair onto the canonical, unmerged project metadata:
only the single CNCF compile dependency's version and `build.cozyVersion`
change. Component identity/version, dependency spelling, other dependencies,
authored keys, source paths and runtime policy remain project-owned. Both
arguments must be present, nonblank Snapshot versions, and the canonical
component must be a Snapshot. Missing or multiple canonical CNCF coordinates
are rejected. Default package admission remains unchanged without these flags.

The actual input JAR must still carry exactly one matching native CNCF runtime
descriptor. Normal compatibility, ABI, provenance and runtime manifest gates
remain mandatory. Runtime `tested`, minimum, maximum and exclusions are never
inferred from source build inputs: the owning project must explicitly authorize
the selected runtime. This route creates development CARs; it does not change
published defaults or publication admission against the canonical compile pair.

Executable evidence: `CozySourceBuildPackagingSpec` in sbt-cozy covers input
selection and argument transport; the same-named Cozy archive specification
covers canonical projection and archive admission through the production entry.
