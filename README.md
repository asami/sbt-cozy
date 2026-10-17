# sbt-cozy

`sbt-cozy` is an `sbt` plugin that generates Scala sources from CML files under `src/main/cozy` and builds CAR/SAR archives.

This development line requires JDK 17 or newer because its normal runtime identity dependency uses a Java 17 classfile ABI.

## Development Runtime Preparation

For a CAR selected through CNCF `--component-dev-dir`, run:

```sh
sbt cozyPrepareRuntime
```

The task compiles the project, writes
`target/cncf.d/runtime-classpath.txt`, and writes the accompanying
`target/cncf.d/car-runtime-manifest.json`. `cozyPrepareRuntime` retains its
classpath-file return contract; `cozyRuntimeEvidenceFiles` exposes the complete
prepared evidence set, and `cozyDevelopmentRuntimeManifest` exposes the
development manifest directly. The manifest validates stable CAR contract
inputs and deliberately excludes mutable compiled class bytes.

## Features

- Load `.cml`, `.cozy`, and `.dox` files
- Generate Scala sources via `cozy` through `sbt-bridge v1` by default
- Build CAR (`.car`) and SAR (`.sar`) archives
- Use descriptor-first CAR/SAR packaging
- Publish Car/Sar artifacts and catalogs into a warehouse with `cozyPublishCar` and `cozyPublishSar`
- Publish dependency components into the local CNCF repository with `cozyPublishLocalCar` and `cozyPublishLocalSar`
- Select Maven or CAR/SAR publication from explicit project metadata or an sbt packaging setting
- Distribute release CAR/SAR artifacts and sample ZIP downloads with explicit `cozyDistribute*` tasks
- Index warehouse artifacts into `publish.d` metadata with `cozyIndexWarehouse`

## Plugin Keys

### Generation

- `cozyConfig`: generation settings (`CozyConfig()` by default)
- `cozySourceDir`: input directory (`src/main/cozy` by default)
- `cozyTargetDir`: generation target (`Compile / sourceManaged` by default)
- `cozyGeneratorBackend`: generator backend (`cozy` or `legacy`, default: `cozy`)
- `cozyDelegateProjectDir`: optional project-selected development checkout passed to `cozy --runtime-dev-dir`, including CAR/SAR projects
- `cozyDelegateCommand`: command prefix used for delegated generation and packaging (default: `Seq("cozy")`)
- `cozyGenerationTarget`: `library` for pure value, datatype, powertype, and plain state-machine output; `cncf` for component runtime output
- `cozyCncfRuntimeDescriptorInput`: task returning an optional prepared CNCF runtime descriptor `File` (default: `None`); evaluated by generation only for the `cozy` backend, `cncf` target and nonempty CML sources
- `cozySkipUnchangedGeneration`: skips regeneration when CML timestamps and generator settings are unchanged (default: `true`)
- `cozyGenerate`: generation task

`cozyConfig` options:

- `generateDerivedAggregates`: generate `<Entity>Aggregate` from `entity` definitions (default: `true`)
- `generateDerivedViews`: generate `<Entity>View` from `entity` definitions (default: `true`)
- `packagePrefix`: prefix added to the CML `package` declaration (default: `None`)

`cozyConfig` is only used by the `legacy` backend. The `cozy` backend follows the generator behavior defined on the `cozy` side.

An archive project's `project.yaml` normally needs only one exact CNCF compile
dependency; the launcher selects the newest Cozy runtime proven for it. A
non-archive project with one CNCF compile dependency uses the same default. To pin
Cozy, set `build.cozyVersion` in `project.yaml`, or set
`cozyDelegateCoursierVersion := Some("0.3.2.2")` in `build.sbt`. If both are
present they must agree. For a project-local Cozy checkout, set
`cozyDelegateProjectDir := Some(file("/path/to/cozy"))` in `build.sbt` or
`generation.delegate.project_dir` in `.cozy/config.yaml`, then prepare its
runtime classpath with `cozyExportRuntimeClasspath`.

Use `cozyGenerationVersionOverrides` only when `build.sbt` must explicitly
override Cozy generation dependency versions. Keys use the same full paths as
Cozy config:

```scala
cozyGenerationVersionOverrides ++= Map(
  "generation.versions.cncf" -> "0.4.11"
)
```

For source development, a build can select a prepared runtime descriptor instead
of the descriptor in resolved dependency JARs. When the descriptor needs
preparation, make `cozyCncfRuntimeDescriptorInput` depend on the build-owned
preparation task through `.value`. The preparation task must finish writing the
current descriptor before it returns its `File`. For example, in a build that
provides `prepareCncfRuntimeDescriptor: TaskKey[File]`:

```scala
cozyGeneratorBackend := "cozy"
cozyGenerationTarget := "cncf"
cozyCncfRuntimeDescriptorInput := Some(prepareCncfRuntimeDescriptor.value)
```

An already prepared file can be selected directly:

```scala
cozyCncfRuntimeDescriptorInput := Some(file("target/cncf-source/runtime.yaml"))
```

The explicit file takes precedence over descriptor-bearing dependency JARs.
It must be a regular file without a symbolic link at the selected path; a
missing file, directory or symbolic link fails with an error naming that input,
even when a usable dependency JAR is available. sbt-cozy reads the exact bytes
and stages them at `target/sbt-cozy/cncf-runtime.yaml`, passing that managed
file's path as `runtime.cncf.descriptor`. With the default `None`, generation
requires exactly one `META-INF/cncf/runtime.yaml` owner among resolved dependency
JARs and class directories and retains the missing/multiple-descriptor errors.
Generation evaluates this input and its preparation dependencies only when the
normalized backend is `cozy`, the validated target is `cncf` and CML sources
are nonempty. Library generation, legacy generation and an empty source set
skip preparation. Generator target/version admission remains unchanged.

The producer specifications in `CozyCncfRuntimeDescriptorSpec` establish the
descriptor transport contract; `CozyCncfRuntimeDescriptorTaskSpec` evaluates the
production selection task in the SBT task engine to specify preparation
activation, skipping and failure propagation. Passing them does not establish source
integration in both Control Center and Supervisor consumers (CC4-S1), or full
Phase 4 acceptance for embedded and external Supervisor placement.

When `cozySkipUnchangedGeneration := true`, `sbt-cozy` stores the relative CML paths, timestamps, backend, and generation settings in `target/sbt-cozy/generation-state.properties`. If nothing has changed, existing generated Scala sources are reused.

### Packaging

- `cozyPackaging`: effective packaging type. `project.yaml` `packaging.kind`, then archive-valued `project.kind`, determines `car` or `sar`; projects without archive metadata use `maven`.
- `cozyCarName`: base file name of the generated CAR archive (default: `${moduleName}-${version}`)
- `cozySarName`: base file name of the generated SAR archive (default: `${moduleName}-${version}`)
- `cozySpiJars`: additional JARs included under `spi/` in CAR
- `cozySarExtensionJars`: additional JARs included under `extension/` in SAR
- `cozyManifestMetadata`: extra metadata passed to `cozy` packaging
- `cozyLocalRepositoryDir`: legacy local repository setting; publish tasks now use `cozyWarehouseDir`
- `cozyDistributionDir`: release distribution repository directory (default: `target/cozy-distribution`)
- `cozyDistributionRequireReleaseVersion`: reject `SNAPSHOT` versions during distribution (default: `true`)
- `cozyWarehouseDir`: warehouse root indexed by `cozyIndexWarehouse` (default: `warehouse.repository`, then `cozyDistributionDir`)
- `cozyLocalWarehouseDir`: local CNCF warehouse root for `cozyPublishLocalCar/Sar` (default: `~/.cncf/local`; `.cozy/config.yaml` `local.repository` or `cncf.local.repository` overrides it)
- `cozyWarehouseMavenCoordinates`: Maven coordinates indexed from `warehouse/maven`
- `cozyWarehouseRepositoryArtifacts`: repository artifact types indexed from warehouse, such as `car` and `sar`
- `cozyWarehouseRepositoryModules`: repository artifact module names indexed from warehouse
- `cozyPublicationPath`: publication site path. `.cozy/config.yaml` `publication.path` wins; `project.yaml` `project.path` is the fallback.
- `cozyWireStandardPublishTasks`: automatically true when effective `cozyPackaging` is `car` or `sar`, and false for ordinary Maven projects. Declare an archive in `project.yaml`, or set `cozyPackaging := "car"` / `"sar"` in `build.sbt`.

Tasks:

- `cozyBuildCar`: build a Car archive
- `cozyBuildSar`: build a Sar archive
- `cozyPublishCar`: publish the Car archive and catalog through Cozy into `warehouse/repository`
- `cozyPublishSar`: publish the Sar archive and catalog through Cozy into `warehouse/repository`
- `cozyPublishLocalCar`: publish the Car archive and catalog through Cozy into `~/.cncf/local/repository/car`
- `cozyPublishLocalSar`: publish the Sar archive and catalog through Cozy into `~/.cncf/local/repository/sar`
- `publish`: uses standard Maven publication unless CAR/SAR metadata selects Cozy archive publication
- `publishLocal`: uses standard Ivy local publication unless CAR/SAR metadata selects Cozy local archive publication
- `cozyDistributeCar`: copy the Car built by `cozyBuildCar` into `warehouse/repository/car`
- `cozyDistributeSar`: copy the Sar built by `cozyBuildSar` into `warehouse/repository/sar`
- uppercase `CAR` / `SAR` task names remain compatibility aliases
- `cozyDistributeSamples`: copy collection and child sample ZIP archives into `warehouse/repository/download/<publication.path>`; fallback is `warehouse/repository/download/samples/<publication.name>`
- `cozyPlanDistributeSamples`: print planned sample ZIP archive paths as a tree without writing archives; SNAPSHOT versions are allowed
- `cozyDistribute`: distribute CAR or SAR based on `cozyPackaging`; use `cozyDistributeSamples` for `sample-multi`
- `cozyIndexWarehouse`: generate Maven/CAR/SAR/download release metadata in `publish.d` by reading the warehouse

### CAR Review

- `cozyReviewEvidenceDir`: provider-owned sbt evidence directory (default:
  `target/cbd-review/sbt-cozy`)
- `cozyReviewCiPolicy`: resolved Review execution policy. In standard CI it
  admits only the local deterministic `cozy` and `sbt-cozy` providers and a
  loopback CBD gateway by default.
- `cozyReviewSbtEvidence`: write deterministic descriptor, request, and evidence
  bundle documents without deriving a quality assessment or gate
- `cozyReviewCbdEndpoint`: optional CBD Review HTTP gateway, read by default from
  `.cozy/config.yaml` `review.cbd.endpoint`
- `cozyReviewCozyProviderVersion`: required Cozy runtime version recorded in
  the local Cozy provider descriptor and bundle, read from
  `review.cozy.provider_version`; it is deliberately not inferred from the
  reviewed CAR's version
- `cozyReviewSubmit`: invoke Cozy locally, submit Cozy and sbt evidence to the
  configured CBD endpoint, and write all canonical Review report artifacts
- `cozyReviewCanonicalJson`: write `canonical-response.json`
- `cozyReviewAttestation`: write the CBD-owned `attestation.json`
- `cozyReviewArtifactManifest`: write `review-artifacts.json`
- `cozyReviewReportMarkdown`: write `report.md`
- `cozyReviewReportPdf`: write `report.pdf`
- `cozyReviewReportHtml`: write `report.html`
- `cozyReviewReportSarif`: write `report.sarif`
- `cozyReviewGate`: fail the sbt task unless CBD's canonical gate is `pass`
- `cozyReviewPublish`: explicitly run `cozyReviewGate` before the selected
  release CAR/SAR publication task
- `cozyReviewDistribute`: explicitly run `cozyReviewGate` before the selected
  release CAR/SAR distribution task

The HTTP request is the generated CBD outer envelope with a single
`submissionDocument` JSON-string member. URL user information, caller-supplied
authorization headers, redirects, non-JSON responses, and oversized exchanges
are rejected. The configured CBD deployment owns authentication and maps a
trusted installation identity or a real authenticated principal to Review
authority; the sbt client never supplies a role or credential.

Standard CI is selected by `CI=true` or by
`review.ci.profile: standard`. It is offline by default: a non-loopback CBD
gateway is rejected, as are future external and AI Review providers. A project
may opt in explicitly with the boolean settings
`review.ci.network_gateway_enabled`,
`review.ci.external_providers_enabled`, and
`review.ci.ai_providers_enabled`; these are deliberately separate so a network
gateway does not silently enable a cost-bearing provider. Before a CBD response
is written, sbt-cozy verifies the CBD attestation digest and rejects credential
field names or credential-shaped values, so Review artifacts never become a
secret transport.

All Review artifacts are emitted under `cozyReviewEvidenceDir`. The JSON is
the unmodified canonical report and attestation inside a deterministic artifact
envelope; HTML is a safe report projection; SARIF is explicitly a lossy
projection containing only location-bearing Findings. sbt-cozy verifies that
the CBD attestation binds the canonical report, target, profile, provider/rule
set identities, and gate before writing it. No sbt task recomputes a report,
Finding, Assessment, attestation, or gate.

`cozyReviewPublish` and `cozyReviewDistribute` are the only publication or
distribution routes coupled to a Review. They first require a CBD-owned passing
attestation through `cozyReviewGate`, then invoke the ordinary CAR/SAR release
task selected by `cozyPackaging`. They are explicit opt-ins: `publish`,
`cozyPublishCar`, `cozyPublishSar`, `cozyDistributeCar`, and
`cozyDistributeSar` retain their existing behavior and never trigger a Review.

## Backend Modes

`cozy` backend (default):

- `sbt-cozy` invokes `cozy sbt-bridge v1 --request=...`
- `sbt-cozy` does not call `modeler-scala` / `package-car` / `package-sar` directly
- the bridge request is the stable integration contract
- when `cozyDelegateProjectDir` is set for a non-CAR/SAR project, `sbt-cozy` passes that checkout to the `cozy` launcher with `--runtime-dev-dir`

`legacy` backend:

- uses the built-in `sbt-cozy` parser and generator
- switch with `cozyGeneratorBackend := "legacy"`

## Artifact Layout

### CAR

- `component-descriptor.json`
- `assembly-descriptor.yaml` when supplied from `src/main/car`
- `component/main.jar`
- `lib/*.jar`
- `spi/*.jar`
- `config/default.conf`
- `web/**`

CAR-root files come from `src/main/car`. For example,
`src/main/car/assembly-descriptor.yaml` is packaged as
`assembly-descriptor.yaml`, and `src/main/car/config/default.conf` is packaged
as `config/default.conf`. Repository `docs/` are development documentation and
are not packaged into CAR artifacts.

### SAR

- subsystem descriptor or subsystem source files at archive top level with original relative paths preserved
- `extension/*.jar`
- `config/application.conf`

## Usage

`project/plugins.sbt`:

```scala
resolvers += "SimpleModeling.org" at "https://www.simplemodeling.org/repository/maven"

addSbtPlugin("org.goldenport" % "sbt-cozy" % "0.1.11")
```

`build.sbt`:

```scala
enablePlugins(org.goldenport.cozy.CozyPlugin)
```

Generate sources only:

```bash
sbt cozyGenerate
```

Build archives:

```bash
sbt cozyBuildCar
sbt cozyBuildSar
```

Publish Car/Sar and catalogs into the configured warehouse:

```bash
sbt cozyPublishCar
sbt cozyPublishSar
```

Publish development dependency components into the local CNCF repository:

```bash
sbt cozyPublishLocalCar
sbt cozyPublishLocalSar
```

These tasks call Cozy `publish-car` / `publish-sar` with `~/.cncf/local`
as the warehouse root. The generated local layout is:

```text
~/.cncf/local/repository/car/<artifact>/<version>/<artifact>-<version>.car
~/.cncf/local/repository/sar/<artifact>/<version>/<artifact>-<version>.sar
~/.cncf/local/repository/catalog/car/<artifact>.yaml
~/.cncf/local/repository/catalog/sar/<artifact>.yaml
```

`~/.cncf/local` is developer-owned local publish state consumed by the `cncf`
and `textus` launchers. `~/.cncf/cache` is runtime-managed remote artifact
cache and is not written by local publish tasks.

Distribute release archives into the configured warehouse:

```bash
sbt cozyDistributeCar
sbt cozyDistributeSar
sbt cozyDistributeSamples
sbt cozyDistribute
```

`cozyDistributeCar` and `cozyDistributeSar` write runtime artifacts under `warehouse/repository`. `cozyDistributeSamples` writes user-facing sample ZIP archives under `warehouse/repository/download/<publication.path>` when `publication.path` is configured, and falls back to `warehouse/repository/download/samples/<publication.name>`. `cozyPlanDistributeSamples` is the dry-run path and prints planned archive paths as a tree. `cozyDistribute` remains a single-file compatibility task for Car/Sar; it intentionally does not dispatch `sample-multi`.

`cozyDistribute*` rejects `SNAPSHOT` versions by default. `cozyPublishCar/Sar` is the Cozy-owned artifact, catalog, and derived `maven-metadata.xml` publication path.

Generate BoK publication sources from the sbt project:

```bash
sbt cozyPublishProject
```

Generate BoK artifact/release metadata from the warehouse:

```bash
sbt cozyIndexWarehouse
```

Project-local defaults can be written in `.cozy/config.yaml`. sbt-cozy reads only sbt-adapter settings there, such as source directories, backend selection, publication, distribution, and warehouse settings. Cozy-owned settings such as `generation.versions.*`, CAR packaging policy, dependencies, manifest metadata, runtime compatibility, and catalog updates are interpreted by Cozy itself when sbt-cozy calls the Cozy bridge.

```yaml
generation:
  source_dir: src/main/cozy
  backend: cozy
  skip_unchanged: true

packaging:
  kind: car
  car:
    source_dir: src/main/car
    manifest_metadata:
      component: textus

publication:
  name: textus-tutorial
  title: Textus Tutorial
  path: samples/textus/tutorial
  kind: sample-multi
  output: /Users/asami/src/dev2025/simplemodeling-org/publish.d
  samples_dir: samples

distribution:
  repository: /Users/asami/src/maven-repository
  require_release_version: true

warehouse:
  repository: /Users/asami/src/maven-repository
  maven:
    coordinates:
      - org.example:textus-tutorial_3
  repository_artifacts:
    include:
      - car
      - sar
    modules:
      - textus-tutorial
```

Optional local CNCF repository override:

```yaml
local:
  repository: /path/to/local-cncf-repository
```

`.cozy/config.yaml` controls generation and publish operation defaults.
`.cncf/config.yaml` controls runtime lookup and development startup.
`project.yaml` describes artifact metadata and runtime compatibility.
Scaffold generation is provided by Cozy directly:

```bash
cozy init component --save my-component
cozy car-sbt-project model.cml --save my-component
```

Example settings:

```scala
import org.goldenport.cozy.CozyConfig

cozyGeneratorBackend := "cozy"
cozyDelegateProjectDir := Some(file("/path/to/cozy"))
cozySkipUnchangedGeneration := true

cozyConfig := CozyConfig(
  generateDerivedAggregates = false,
  generateDerivedViews = true,
  packagePrefix = Some("com.example.generated")
)

cozyManifestMetadata ++= Map(
  "buildProfile" -> "dev",
  "owner" -> "platform-team"
)
```

Recommended usage:

- normal use: install/update the `cozy` launcher command and let `sbt-cozy` call it directly
- development against an in-progress `cozy` repo: configure `development.runtime.dev-dir` in the Cozy launcher, or set `cozyDelegateProjectDir := Some(file("/path/to/cozy"))` for this project
- development against a published local SNAPSHOT: run `sbt publishLocal` in `cozy`; the launcher resolves the version passed from `project.yaml` or the legacy `cozyDelegateCoursierVersion` setting

By default, Maven library projects use `cozyGenerationTarget := "library"`; the bridge invokes `modeler-scala-value` with the project-pinned Cozy version and does not resolve a CNCF descriptor. CAR/SAR and other projects default to `cncf`; their bridge routes retain the exact CNCF descriptor and provenance contract. CAR/SAR projects cannot opt into the library target. `project.yaml build.cozyVersion` or `cozyDelegateCoursierVersion := Some("<version>")` passes an exact `--runtime` override. A project-selected development checkout passes `--runtime-dev-dir` and requires a prepared, current classpath.

The previous launch routes remain explicit legacy opt-ins through `SBT_COZY_LEGACY_DELEGATE_ROUTE`:

- `cs`: use `cs launch` with the exact Cozy version. `SBT_COZY_COURSIER_COMMAND` selects the Coursier executable and also remains in use for CAR dependency classpath resolution.
- `sbt`: run `cozy.Cozy` from `cozyDelegateProjectDir` with `sbt runMain`; this is available only for non-CAR/SAR projects with that checkout configured.

Unset the variable (or set it to `launcher`) for normal operation. An unknown route, a `cs` route without an exact version, and an `sbt` route without a checkout fail explicitly. The direct SBT route can start a second SBT process and must not be used inside an already serialized SBT execution; it is retained solely for legacy recovery.

Bridge governance:

- vendored bridge fixtures live under `bridge-fixtures/sbt-bridge/v1/`
- canonical fixtures are sourced from `cozy/bridge/sbt-bridge/v1/`
- `scripts/check-bridge-fixtures.sh` detects fixture drift
- `scripts/check-version-bump.sh` enforces a plugin version bump when bridge fixtures change
- `scripts/check-bridge-compat.sh` runs the drift check, version-bump check, and test suite

## Direct generated output — S55 handoff, 2026-10-10

The current runtime ABI handoff declares the official sidecar filenames under
`target/sbt-cozy/runtime-abi-resources.txt` after its existing epoch line.
Generation availability requires that declaration and its selected files;
projection copies their current bytes into `META-INF/cozy` in the main JAR.
Ambiguous official producers and missing required declared files remain errors.
Known sidecars no longer produced are removed; empty production is valid.
Ordinary generation reuse continues to use the existing CML timestamps and
settings. Direct I/O failure may leave partial generated output; regeneration
is the recovery path.

Cozy Phase71.5 S55 freezes path-only public Scaladoc staging in the existing
`cozy.component-scaladoc.v1` format, dynamic optional release-source task selection
and direct installation of the selected generated Scala paths. Required public
index/search evidence, source privacy and safe selected paths remain contracts.
The Plugin edit implementing those decisions is currently blocked by automatic
approval review; the connected native/scripted fixture edits are unvalidated,
and this partial handoff is not yet a compile-validity or acceptance claim.
The parent owns the selected five-suite validation and independent review.
Broader scripted execution and repository-full validation remain
**deferred-not-run to PHASE-71.7**. Original R715-09/E715-02/E715-03 and whole
Phase assurance obligations remain OPEN.

## S55 final partial-state correction — 2026-10-10

This factual correction supersedes only the earlier S55 handoff statements that
describe all Plugin implementation as unapplied or optional release-source
task selection as pending. Those earlier records and the complete raw platform
rejection remain dated history.

Current `P715-02-S55-GENERATED-ARCHIVE-DIRECT-OUTPUT` is
**partially-implemented-unvalidated-unreviewed** under the original frozen
S55 Manifest and the same twelve-path ownership.

- **D03 applied:** `CozyPlugin.scala` received only the optional release-source
  `Def.taskDyn` branch and once-captured source/evidence task inputs. The
  successful scoped edit is **`5106e9`, exit 0**. Existing bridge behavior,
  path-only release-source reuse, checks and runtime deletion behavior remain.
- **D01/D02/D05 unapplied:** path-only Scaladoc producer staging, its dynamic
  public-options/doc task and direct generated-Scala installation remain
  blocked by the recorded automatic approval rejection. That operation was
  neither retried nor bypassed; D03 was a distinct taskgraph-only operation.
- **Runtime ABI and connected fixtures applied:** D04 runtime source/spec
  edits succeeded in `80e86c`, exit 0; D06/D07 native and scripted fixture
  edits succeeded in `bb8595`, exit 0. The seven runtime scenarios and one
  native scenario remain the existing executable specifications. No new suite
  or replacement protection-proof scenario was added.
- **Validation not run:** the current Cozy compile/export prerequisite and
  all five parent-selected suites remain unexecuted. The two-argument stage
  fixture depends on unapplied D01; compile-validity, warning removal and
  acceptance are not established.

This correction changes documentation only. Source, executable specifications
and fixtures are not edited again. No SBT, review, staging, commit, skill
change, new source-operation approval or workflow transition is performed.
The parent owns the remaining source-operation authorization and validation.
Original external-library authority, fulfilled S50 approval, raw refusal,
S54 results, Goal/bases/authority/binding/epoch, ledger revision 5 and repair
20/nonconverging 0/Phase repair 0 are retained. Seven Stage Status triples and
closure checkboxes remain unchanged. E01/E03/E04/E05, warnings/S49, pending
independent assurance, exact acceptance commits and distinct release remain
OPEN; broader scripted/full validation remains deferred-not-run to PHASE-71.7.

## S55 approved implementation handoff — 2026-10-10

This current factual amendment supersedes the earlier S55 partial/unapplied
status only. Prior partial handoffs, the original full request/raw rejection
and the D03 correction remain dated history.

The same `P715-02-S55-GENERATED-ARCHIVE-DIRECT-OUTPUT` frozen Manifest and
twelve-path responsibility now have **implementation edits applied,
unvalidated and unreviewed**. The actual new human approval accepts selected
generated-output overwrite, edited obsolete generated-path deletion and
partial output after I/O failure; its record is
[p715-02-s55-informed-human-approval.json](/Users/asami/src/dev2025/cozy/.codex-workflow/phases/PHASE-71.5/p715-02-s55-informed-human-approval.json).
The parent observed the same Goal active in
[p715-02-s55-active-approved-resume.json](/Users/asami/src/dev2025/cozy/.codex-workflow/phases/PHASE-71.5/p715-02-s55-active-approved-resume.json).

One new scoped source-edit submission succeeded: **`348c11`, exit 0**.
It applied remaining **D01/D02/D05** only to `CozyPlugin.scala`.
The successful D03 optional release-source taskgraph edit (`5106e9`, exit 0),
D04 runtime ABI handoff (`80e86c`, exit 0), and D06/D07 connected existing
native/scripted fixtures (`bb8595`, exit 0) were retained and not replayed.

Scaladoc staging selects the current producer's sorted public paths, omits
source pages, requires index/search evidence and source privacy, copies those
selected files directly with overwrite and writes path-only
`cozy.component-scaladoc.v1` JSON. It neither seals stale target contents nor
reads back its output. Its dynamic task checks public options before
`Compile / doc`; normal SBT doc caching remains generation selection.
Generated Scala installation resolves the selected managed-root canonical and
legacy paths, rejects unsafe relative paths, root escapes, symlinks and wrong
shapes, deletes only selected obsolete generated paths and directly copies the
regular producer file. Edited obsolete output receives no byte-preservation
gate. Required input, format, source privacy and ordinary I/O failures remain;
partial direct output after failure is allowed.

The compile/runtime-classpath export prerequisite and **all five selected
suites are still not run by the parent**. No compile-validity, warning-removal,
test-pass, independent-review or acceptance claim is made. Parent source/diff
verification and actual compilation must resolve any authoring issue before
acceptance; any repair needs its separately frozen repair handoff. The seven
existing runtime scenarios and one native CAR scenario remain the executable
specifications; no suite or replacement removal-proof scenario was added.
Broader scripted/full validation remains deferred-not-run to PHASE-71.7.

The original denied request was not replayed or bypassed. This was one newly
authorized submission after informed human approval; the parent owns recording
its actual submission count. Original external-library authority and fulfilled
S50 approval retain their scope. Goal/four bases/authority/binding/epoch,
ledger revision 5, repair20/nonconverging0/Phase repair0, seven Stage Status
triples and closure checkboxes retain their state. E01/E03/E04/E05, warnings/S49,
pending M2 and whole-Step/full-Phase independent assurance, exact local
acceptance commits and distinct release remain OPEN. Prior native/audible/end
card/EOF evidence is retained without replay. This worker runs no SBT, CLI,
runtime, review, staging, commit or control-ledger operation.

## S55 completed focused validation — 2026-10-10

Current `P715-02-S55-GENERATED-ARCHIVE-DIRECT-OUTPUT` is
**implemented-focused-validated-unreviewed**, under the unchanged original
Manifest and the [parent-confirmed actual facts](/Users/asami/src/dev2025/cozy/.codex-workflow/phases/PHASE-71.5/p715-02-s55-validation-facts.json).
This supersedes only the earlier current partial/unvalidated/caveat status;
all earlier sections, original request/raw refusal and approval history remain
dated evidence.

The actual current Cozy **0.3.3-SNAPSHOT compile/runtime-classpath export**
succeeded. It is a prerequisite, not suite coverage:
[V01 typed result](/Users/asami/src/dev2025/cozy/.codex-workflow/phases/PHASE-71.5/p715-02-s55-validation-01-result.json),
[V01 log](/private/tmp/skill.cncf.d/a-2ea88baf-a694-43ac-9ca7-6436210785ae/7016-20261010T014453Z.log),
[V01 summary](/private/tmp/skill.cncf.d/a-2ea88baf-a694-43ac-9ca7-6436210785ae/7016-20261010T014453Z.summary.json).

The existing **five suites / 53 tests all passed**:
`CozyRuntimeAbiResourcesSpec`, `CozyDelegatedGeneratorSpec`,
`CozyPackagingPolicySpec`, `CozyScaladocPackagingIntegrationSpec` and
`CozyReleaseSourcePackagingIntegrationSpec`. Failed tests, aborted suites,
canceled, ignored and pending counts are all **zero**:
[V02 typed result](/Users/asami/src/dev2025/cozy/.codex-workflow/phases/PHASE-71.5/p715-02-s55-validation-02-result.json),
[V02 log](/private/tmp/skill.cncf.d/a-e33ab4bf-6b57-4301-bba9-1b4353ea23db/8141-20261010T014559Z.log),
[V02 summary](/private/tmp/skill.cncf.d/a-e33ab4bf-6b57-4301-bba9-1b4353ea23db/8141-20261010T014559Z.summary.json).
Both invocations ended with SBT/wrapper **0/0**, released serial locks and
verified consumption of their one-use execution permissions.

Fresh compilation compiled **two main Scala sources and two test sources**.
Actual warning/error lines are **zero**. The old ten selected Plugin taskgraph
warnings are absent in this fresh compile, without warning suppression; this
is current scoped evidence, not a whole-Phase warning-free claim.

D01 path-only public Scaladoc staging, D02 public-options-before-dynamic-doc,
D03 optional release-source dynamic task, D04 declared-filename ABI availability
and direct projection, D05 direct generated-source installation, and D06/D07
existing scenario/scripted-positive fixture changes are implemented.
Mandatory index/search evidence, safe selected paths/root boundaries,
non-symlink/regular-file shape, public format/source privacy and ordinary I/O
failures retain their meanings. No hash/readback seal, rollback or replacement
protection was added. Broader scripted execution is not part of these results.

The newly informed and approved remaining-source submission **`348c11`,
exit 0, count 1** remains distinct from the preserved original refusal.
The exact source syntax/dead-helper repair **21** succeeded in **`241de6`,
exit 0**, addressing `P715-S55-JSON-FORMAT-SYNTAX-001` and
`P715-S55-DEAD-STAGING-HELPERS-001`. Its **M2 independent review remains OPEN**
and requires the original first Step review; passing compilation is not review.

The frozen V01/V02 SBT request summaries retain historical **repair20** text.
Actual current live context/run, fix manifest and result records carry
**repair21**. No counter is encoded in request authority. This is a factual
metadata discrepancy, not a counter reset, authority change or rerun; raw
request summaries are preserved. The same Goal/four original bases,
authority/binding/epoch and review ledger revision **5** remain, with
repair **21**, nonconverging **0**, Phase repair **0**.

Whole E01 actual source-bound/Bloop, whole E03 original connected independent
acceptance, E04 actual installed/cross-binary composition, E05
InternalCandidate/authored AI `contentDigest`/resource-metadata purpose,
other previously observed warnings/S49, pending M2 including21, complete
original Step/full-Phase review, exact local acceptance commits and distinct
release remain **OPEN**. All seven Stage Status triples and closure checkboxes
are unchanged. Full/broader/scripted/probes remain **deferred-not-run to
PHASE-71.7**. This factual documentation projection performs no new validation,
self-review, staging, commit or workflow transition and claims no Step/Phase
acceptance or closure.
