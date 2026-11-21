# Explicit development artifact publication and consumer selection

Date: 2026-11-12
Status: design proposal and implementation handoff; no execution qualification claimed.

## Objective and authorized scope

Publish a modified library A into a task-private repository and build/test B
using exactly that A, including when A is a transitive dependency. Do not
overwrite ordinary local publications or let another task select this output
accidentally. Consumer build.sbt and project/plugins.sbt changes are allowed.
This is the user-approved design premise, not a requirement to support
unmodified consumer builds.

sm-workflow owns the development dependency graph, producer ordering, selection,
execution lifecycle, retention and consumer acceptance. CNCF owns admitted SBT
execution and workspace transport. sbt-cozy owns SBT publication, settings,
resolution, classpath observation and result reporting. Lint additions are not
part of the mechanism or its acceptance criteria.

## Current evidence and proposed change

The working tree already contains an uncommitted
[overlay specification](../../../spec/development-artifact-overlay.md),
CozyDevelopmentArtifactOverlay.scala, CozyPlugin integration and two test files.
They belong to ongoing development and were only read for this note.

The current install implementation runs from Global/onLoad, appends settings
to enabled projects, resolves an ordinary baseline with selected overrides
removed, and then installs selected dependencies/overrides/private resolver.
The publisher uses packageBin and makeIvyXml and has a private-layout completion
record. None of this inspection establishes that the implementation or tests pass.

Keep the scoped transport and private publication concept. Prefer explicit
build settings over automatic loaded-build mutation. In particular, a successful
ordinary baseline update must not be a prerequisite: the ordinary version of A
may not exist anywhere yet. A baseline can be optional diagnostic evidence, not
a second resolution whose success gates use of a valid development publication.
Update the existing specification through the owning implementation work; this
journal does not silently replace its still-active contract.

## Selected design

### 1. Private coordinates and repository

Retain the adapter's collision-free scoped version convention:
originalVersion-smdev-{scopeLength}-{scope}-{publicationLength}-{publication}.
A new publication gets a new identity/version; no overwrite of an existing
coordinate. Keep organization and exact cross-versioned module identity
(e.g. a_2.13 versus a_3) unchanged. The private filesystem repository contains
JARs and Ivy descriptors; use one supported layout consistently.

A dedicated smDevelopmentArtifactPublish task publishes into the explicitly
selected private repository. Do not redirect ordinary publishLocal, copy into
~/.ivy2/local or ~/.m2/repository, change global repositories, or flush caches.
Shared caches may hold these distinct immutable coordinates; isolation is by
coordinate plus explicit selection, not by globally isolating all SBT caches.

Prepare the complete module directory in staging on the same filesystem and
promote it without replacement only after packaging and metadata succeed.
Use an exclusive destination claim and completion marker, with failures leaving
an unselectable partial publication. Do not implement check-then-overwrite.
Concurrent duplicate publication, interrupted promotion and existing coordinates
need explicit tests. A completion marker is an output receipt, not an approval
or a new tamper-defense ledger.

Use SBT's publication metadata facilities where possible. Preserve dependency
configuration mappings, exclusions, classifiers, optional/transitive attributes
and actual cross-version identities; do not reconstruct descriptors from a
flat list of resolved JARs. Record effective selected dependencies separately
and check consistency with the published descriptor.

### 2. Explicit consumer opt-in

Proposed API names below are design placeholders, not current public keys.
Extract a small development-artifact AutoPlugin reusable without activating
Cozy generation or CAR packaging. Existing CozyPlugin can share that implementation.
Consumer setup is deliberately visible:

```scala
// project/plugins.sbt: use the normally installed, qualified sbt-cozy plugin
addSbtPlugin("org.goldenport" % "sbt-cozy" % "<qualified-version>")

// build.sbt: proposed API
lazy val app = project.in(file("."))
  .enablePlugins(CozyDevelopmentArtifactsPlugin)
  .settings(
    libraryDependencies += cozyDevelopmentDependency(
      "example" %% "a" % "1.0.0-SNAPSHOT"
    ).value,
    cozyDevelopmentModules += ("example" %% "a" % "1.0.0-SNAPSHOT")
  )
```

cozyDevelopmentDependency is a setting-returning helper: it preserves the
ModuleID attributes and maps a direct dependency to its selected coordinate.
cozyDevelopmentModules declares expected original coordinates, including
transitive-only A when B directly depends on C and C depends on A. Implement
the helper with SBT Def.Initialize/setting composition and verify it in a
real loaded build before freezing this API.

The input path still arrives via sm.artifact.selection and the existing
cncf.scoped-sbt-input.v1 envelope with its nested cozyArtifact contract.
Absent input means identity mapping and ordinary resolution. Present input
must be parsed and validated before resolution; an empty/bad path, malformed
input or unavailable publication is an error, never an opt-out.

Plugin settings add the private resolver, apply selected dependencyOverrides
to the relevant projects/configurations and validate the effective settings.
Explicit settings avoid onLoad ordering as a business mechanism. Project
settings can still override plugin defaults, so inspect the resulting resolver
and override set and fail on conflicting mapped entries. Do not silently
discard a project author's override. Unmapped repositories/dependencies retain
ordinary behavior. Configuration-specific overrides must be covered.

### 3. Direct and transitive resolution

For direct dependencies the helper checks the declared original revision and
selects the exact development coordinate. For transitive dependencies use the
selected overrides, with the expected original coordinate declared in the
consumer contract. Observe the selected graph and available descriptor/caller
edges; do not require fetching the original A just to prove its replacement.

Reject contradictory mapped revisions/Scala identities rather than letting
eviction silently determine a winner. If the resolver cannot provide enough
edge evidence for a requested compatibility check, return an explicit unsupported
case; do not invent a successful baseline.

An override selects revisions but does not prove that the intended dependency
was present or used. Check every mapping designated for this consumer against
actual update results and Compile/Test managed classpaths. A transitive-only
mapping not encountered in the effective graph is an error, not an injected
direct dependency. Ensure the selected coordinate wins and no original A JAR
also reaches the classpath (including a conflicting unmanaged JAR).

C -> A and A -> D are both relevant: if D is also under development, publish D
first and compile/publish A with the same selected D. Persist that dependency
relation in A's metadata. The consuming selection must agree with that closure;
conflicting development publications must fail. Dependencies of selected A
continue through normal resolution except those explicitly mapped.

Use actual resolved coordinates and artifact provenance in the result report.
SBT/Coursier may return a cache path rather than the repository path: accept
attributable cache materialization of the exact immutable publication, not an
assumption that every classpath path lies under the private repository.
No ordinary-coordinate fallback is permitted. Project dependencies that bypass
external artifact resolution, sbt-plugin/meta-build dependencies, unsupported
classifiers and binary mappings must be explicitly supported or refused.
The initial milestone covers ordinary JVM libraries; do not silently replace
a project reference or claim meta-build support.

### 4. End-to-end operation

1. sm-workflow freezes task scope and the producer/consumer selection and asks
   CNCF to run the producer's private publication task.
2. sbt-cozy validates input, applies any selected dependencies, compiles/packages,
   stages/promotes the publication and emits actual coordinates and metadata.
3. sm-workflow uses the completed publication receipt to form B's selection.
   No directory scanning for the newest JAR or mutable global selection.
4. CNCF starts B with that explicit input. sbt-cozy checks selection, resolves
   the selected graph and validates classpaths before compilation/test work.
5. Return per-project/configuration resolved coordinates, artifact origins,
   selected publications and actual compile/test outcome. A settings dump or
   successful process exit alone is not consumer acceptance.
6. A later ordinary invocation without input uses ordinary dependencies.
   Cleanup of the task repository belongs to sm-workflow after consumers finish.

## Implementation slices

1. Freeze and test the explicit settings/helper API in a small SBT build;
   factor publication/selection logic from onLoad and generation-specific tasks.
2. Complete private publication using SBT metadata and exclusive promotion.
3. Implement explicit direct mapping and transitive override validation;
   remove mandatory ordinary-baseline resolution, preserving clear mismatch errors.
4. Connect classpath/provenance checks and typed per-project output before and
   after compile/test, including cached and configuration-specific resolution.
5. Update producer and consumer sample build.sbt files and the existing spec,
   document unsupported cases, then exercise the real publish-to-consumer path.
6. Return qualified plugin version, build edits, selection examples, actual
   test evidence and limitations to sm-workflow. Its integration acceptance
   remains separate from sbt-cozy's adapter qualification.

## Acceptance scenarios

- Ordinary no-input build keeps its previous dependencies and behavior.
- A new unpublished original coordinate works solely from the valid private
  development publication; no ordinary baseline artifact is required.
- Direct B -> A and transitive B -> C -> A compile against an API present only
  in development A. Also run a test observing a development-only value.
- A -> D uses selected development D in producer compilation, metadata and B.
- Two task scopes publish different A content and concurrently build different
  consumers. Verify each observes its own API/value, including warm-cache runs.
- Missing input/publication/JAR/descriptor, incomplete publication, wrong
  scope/module/version, absent transitive mapping and conflicting closure fail
  despite a usable ordinary A in normal repositories.
- Consumer override/resolver/configuration precedence, cross-built modules,
  repeated sbt reload and multi-project builds cannot bypass selection.
- Interrupted/duplicate publication never overwrites completed output.
  Ordinary local publication directories remain unchanged.
- Preserve successful producer output across consumer failure; fail the
  consumer honestly and re-collect results without republishing.
- Qualify using actual SBT producer publication and consumer compile/test,
  rather than hand-built success receipts. Keep lint scope separate.

No implementation edits, SBT runs, commits or publications were made for this
journal. Existing uncommitted development was preserved.

## SBT references

The design uses SBT's existing mechanisms; the adapter adds explicit selection
and verification rather than a separate dependency resolver:

- [Library management](https://www.scala-sbt.org/1.x/docs/Library-Management.html):
  libraryDependencies and dependencyOverrides.
- [Resolvers](https://www.scala-sbt.org/1.x/docs/Resolvers.html):
  a named file repository with an explicit Ivy layout.
- [Publishing](https://www.scala-sbt.org/1.x/docs/Publishing.html):
  publication comprises descriptors and artifacts, including cross-version naming.

These references explain the primitives, not proof of the proposed adapter.
