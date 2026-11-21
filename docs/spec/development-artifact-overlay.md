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
mappings and unmanaged JARs are rejected. The generator resolution report
preserves its ordinary and classified artifacts as distinct identities. Builds that replace the plugin validation tasks or compile/test wrappers are unsupported. The initial scope is ordinary JVM
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

## Version 2 batch and generator selection

The in-progress `sbt-cozy.development-artifact.v2` producer retains the same
envelope and root constraints. Its `producers` array contains explicit `project`,
`organization`, exact cross-versioned `module`, selected `version`, `jar` and
`ivy` for every member. Project IDs and module coordinates must be unique.
Project references are limited to declared projects in the same build. All
declared projects are packaged before promotion; cross-member metadata binds the
selected revisions. Each member has a v2 control record and the common record
at `.batches/<scope>/<publication>/completed.json` is written only after all
members have been promoted. A consumer verifies the entire declared batch,
including every member's JAR, descriptor and control, before selecting a member.
An interrupted publication remains unavailable; reusing its claimed identity is
not a recovery mechanism.

A v2 consumer can supply a separate `generator` object with `cozyVersion`,
`modelerVersion`, `batch` and `mappings` using the same mapping fields described
above. Generator mappings must belong to the named completed v2 batch. The
versions specify the original Cozy and SimpleModeler roots; selected mappings
override those exact roots or their development closure. SBT resolves this graph
separately from the consumer graph. For selected development modules, the
resolver's JAR is compared with the immutable private publication and the child
JVM uses the private JAR. Resolution evidence records both the resolver path and
the launch path. Ordinary modules use their resolved paths.

With both plugins enabled, the selected Cozy path uses the fixed `cozy.Cozy`
entry point and `sbt-bridge v3 --request` for development generation. The
selected producer uses one public SBT `DependencyResolution.update`; the v3
resolution is constructed in memory from that operation and compared with the
child's `receivedResolution`. The request contains `version`, `action`,
`selection`, `roots`, `resolution`, `batchRecord`, `arguments` and `settings`.
The resolution includes request roots, exact overrides, requested configurations,
public report rows and callers, module and artifact identities, dependency
edges, ordered runtime artifact keys, producer JVM facts and resolver
configuration. A key includes selected module, artifact name, type, extension,
classifier and extra attributes. Report rows preserve actual target revisions,
eviction and missing-artifact information and resolver names; JavaFX classifier
artifacts and self-edges remain part of the selected closure. The fixed child
reports its actual classpath, loaded origins, Cozy and SimpleModeler BuildInfo,
generation outcome and file inventory. The parent reconciles these with the
current in-memory producer resolution and actual files.

The v3 batch is the complete immutable publication, recorded at
`.batches/<scope>/<publication>/completed-v3.json`. Each member supplies a
module with exact organization, name, selected and base versions, Ivy descriptor
and v3 control paths, plus all published artifact keys and publication files.
The control is `completed-v3.json` in the member revision directory. All batch
members, controls, artifact files and Ivy declarations are checked even when a
consumer selects only a subset. Consumer resolution artifacts additionally carry
the actual resolver file, launch file and runtime flag; these are per-operation
facts and are not fields of the immutable published artifact. Runtime classpath
order comes from the selected SBT report. Development artifact resolver and
launch bytes must agree. Ordinary and legacy v1/v2 input behavior remains
available, and no ordinary baseline resolution is required.

Cozy validates structural consistency and observes the actual child JVM,
origins, backend and generated files. The SBT adapter owns the public update and
must correlate its result with that child. A JSON claim alone does not establish
that SBT performed the update. Internal resolver profile traces and a separate
effective-POM engine are not required; raw dependency expressions and optional
diagnostics are not invented. Invalid inputs, inconsistent or incomplete
batches, resolution conflicts and failed generation fail rather than falling
back to the ordinary launcher. Selection changes take effect on a new SBT
invocation or supported reload; removing the property detaches selection on the
next invocation.

The v3 path is under implementation and remains unqualified. Actual native
checks, independent review, commit and normal local plugin publication are
required before delivery; the contract description is not evidence they passed.

## Qualification

Executable specifications must cover schema and value validation as well as
actual producer publication and consumer compilation through the enabled plugin.
They must cover ordinary and explicitly opted-in builds, including a never-published original coordinate, invalid and unavailable inputs,
direct and transitive selection, project setting precedence, scope and consumer
isolation, incomplete and existing publication, cross-module coordinates, JAR
origin and nonpollution of shared local Ivy artifacts. A process exit alone is
not publication or resolution evidence.
