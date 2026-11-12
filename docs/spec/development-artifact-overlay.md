# Development artifact overlay

Status: implementation in progress; qualification is required before use.
The explicit selection journal dated 2026-11-12 controls this design.

## Ownership and transport

CNCF admits and launches the scoped SBT operation using
`cncf.scoped-sbt-input.v1`. Its input file is passed by the per-build system
property `sm.artifact.selection`. sm-workflow owns lifecycle and the immutable
selection; sbt-cozy interprets that file, applies the selection to the loaded
build and reports actual results. An absent property leaves ordinary sbt
behavior unchanged. A present but invalid, foreign, inconsistent or unavailable
input fails rather than falling back to ordinary dependencies.

The CNCF envelope contains `schema`, `kind`, `component`, `buildWorkspace`,
`ownerWorkspace`, `scope` and `selection`; producer input also contains
`publication`, `repository` and `staging`. CNCF workspace values are identities,
not paths. Paths in the adapter's nested data must be explicitly provided and
validated against their admitted roots. IDs follow the CNCF transport's ASCII
and length constraints. The input is a regular UTF-8 JSON file no larger than
64 KiB.

## Adapter design boundary

The nested JSON object is `cozyArtifact` with schema
`sbt-cozy.development-artifact.v1`. It contains absolute `buildRoot`,
`ownerRoot`, `repositoryRoot`, `resultFile`, copies of `component`,
`buildWorkspace`, `ownerWorkspace`, `scope`, and `selection`, and an array `mappings`. Each
mapping has `requested` and `selected` objects, each with `organization`,
`module` (the exact resolved, cross-versioned module name) and `version`, plus
`jar` and `ivy` paths plus `publication` and `publicationSelection` identities
for the selected publication. The producing selection may differ from the
consuming selection. For a producer, it also
contains `producer` with `organization`, `module`, `version`, `jar`, and `ivy`;
the declared selected version is
`originalVersion-smdev-{scopeLength}-{scope}-{publicationLength}-{publication}`;
lengths count ASCII characters and disambiguate identifiers containing hyphens.
Paths are absolute and must resolve under the owner root. The repository and
staging envelope values are owner-relative paths. Producer output paths identify
the final Ivy-layout files inside that repository. The result file is an actual
output record and is not selection authority.

The nested versioned adapter contract must identify both actual build and owner
roots, agree with the envelope identities and bind a selection to the exact
requested and selected organization, module, version and cross-module names.
It must explicitly identify the scope-private repository and result paths; the
producer contract also declares the producer module, output and publication.
Validate roots and paths, including traversal and symlinks, before use. A
consumer must reject a selection whose completed publication, Ivy descriptor or
JAR is unavailable or contradicts the supplied mapping. There is no automatic
latest lookup, mutable ambient selection, or second authoritative ledger.

Consumers explicitly enable `CozyDevelopmentArtifactsPlugin`, which does not
activate Cozy generation. `cozyDevelopmentDependency` maps an explicitly
wrapped direct dependency, and `cozyDevelopmentModules` declares expected
original coordinates including transitive-only mappings. The plugin adds the
private resolver and selected overrides. It must detect conflicting effective
project and configuration settings, rather than discard authored overrides.
An absent property preserves ordinary resolution. No ordinary baseline update
is required: an original coordinate may never have been published.

A consumer opts in explicitly, including transitive-only expected modules:

```scala
enablePlugins(org.goldenport.cozy.CozyDevelopmentArtifactsPlugin)
libraryDependencies += cozyDevelopmentDependency("example" %% "a" % "1.0").value
cozyDevelopmentModules += ("example" %% "a" % "1.0")
```

A transitive-only module is listed in `cozyDevelopmentModules` but is not added
to `libraryDependencies`. Producer builds enable the same plugin and call
`smDevelopmentArtifactPublish` with a producer input. Any selected direct
producer dependencies use the helper as well, so publication metadata carries
the selected revisions.

Every declared mapping must occur in the actual graph and selected Compile/Test
classpath. No transitive mapping is injected as a direct dependency. Reports
must identify actual project, configuration, resolved coordinates, classpath
origins and outcomes; cached copies are acceptable when attributable to the
selected immutable JAR. Unsupported project references, sbt-plugin/meta-build
mappings, classifiers and unmanaged JARs are rejected. Builds that replace the plugin validation tasks or compile/test wrappers are unsupported. The initial scope is ordinary JVM
libraries and Scala-cross modules.

The fixed `smDevelopmentArtifactPublish` task compiles and packages the producer
using its selected dependencies, writes matching Ivy metadata and the JAR to a
scope-private staging area, and promotes a complete publication into the
explicit private repository without overwriting a coordinate. Coordinates bind
the explicit scope and publication and preserve the exact ordinary Scala-cross
module identity. Selecting sbt-plugin or meta-build dependencies is unsupported.
The sbt-cozy plugin's own eventual normal delivery uses its actual SBT/Scala
cross-version coordinates separately from this selection boundary.
Incomplete staging is never selectable. The producer report records actual
origin, effective dependencies and output paths. This task does not call or
change ordinary `publishLocal`.

The completion record identifies its schema, scope, publication, producer
selection and actual Ivy coordinates and dependencies. Consumers compare these
fields against the explicit mapping and descriptor before using the artifact.

## Qualification

Executable specifications must cover schema and value validation as well as
actual producer publication and consumer compilation through the enabled plugin.
They must cover ordinary and explicitly opted-in builds, including a never-published original coordinate, invalid and unavailable inputs,
direct and transitive selection, project setting precedence, scope and consumer
isolation, incomplete and existing publication, cross-module coordinates, JAR
origin and nonpollution of shared local Ivy artifacts. A process exit alone is
not publication or resolution evidence.
