# sbt-cozy

`sbt-cozy` is an `sbt` plugin that generates Scala sources from CML files under `src/main/cozy` and builds CAR/SAR archives.

## Features

- Load `.cml`, `.cozy`, and `.dox` files
- Generate Scala sources via `cozy` through `sbt-bridge v1` by default
- Build CAR (`.car`) and SAR (`.sar`) archives
- Use descriptor-first CAR/SAR packaging
- Publish CAR/SAR artifacts into a local development repository with `cozyPublishCAR` and `cozyPublishSAR`
- Distribute release CAR/SAR artifacts with explicit `cozyDistribute*` tasks
- Index warehouse artifacts into `publish.d` metadata with `cozyIndexWarehouse`

## Plugin Keys

### Generation

- `cozyConfig`: generation settings (`CozyConfig()` by default)
- `cozySourceDir`: input directory (`src/main/cozy` by default)
- `cozyTargetDir`: generation target (`Compile / sourceManaged` by default)
- `cozyGeneratorBackend`: generator backend (`cozy` or `legacy`, default: `cozy`)
- `cozyDelegateProjectDir`: optional path to the delegated `cozy` project during development
- `cozyDelegateCommand`: command prefix used for delegated generation and packaging (default: `Seq("cozy")`)
- `cozySkipUnchangedGeneration`: skips regeneration when CML timestamps and generator settings are unchanged (default: `true`)
- `cozyGenerate`: generation task

`cozyConfig` options:

- `generateDerivedAggregates`: generate `<Entity>Aggregate` from `entity` definitions (default: `true`)
- `generateDerivedViews`: generate `<Entity>View` from `entity` definitions (default: `true`)
- `packagePrefix`: prefix added to the CML `package` declaration (default: `None`)

`cozyConfig` is only used by the `legacy` backend. The `cozy` backend follows the generator behavior defined on the `cozy` side.

When `cozySkipUnchangedGeneration := true`, `sbt-cozy` stores the relative CML paths, timestamps, backend, and generation settings in `target/sbt-cozy/generation-state.properties`. If nothing has changed, existing generated Scala sources are reused.

### Packaging

- `cozyPackaging`: default packaging type (`car` or `sar`)
- `cozyCarSourceDir`: CAR-root source directory (default: `src/main/car`)
- `cozyCarName`: base file name of the generated CAR archive (default: `${moduleName}-${version}`)
- `cozySarName`: base file name of the generated SAR archive (default: `${moduleName}-${version}`)
- `cozySpiJars`: additional JARs included under `spi/` in CAR
- `cozySarExtensionJars`: additional JARs included under `extension/` in SAR
- `cozyManifestMetadata`: extra metadata passed to `cozy` packaging
- `cozyLocalRepositoryDir`: destination directory for `cozyPublish*` tasks (default: `target/cozy-repository`)
- `cozyDistributionDir`: release distribution repository directory (default: `target/cozy-distribution`)
- `cozyDistributionRequireReleaseVersion`: reject `SNAPSHOT` versions during distribution (default: `true`)
- `cozyWarehouseDir`: warehouse root indexed by `cozyIndexWarehouse` (default: `warehouse.repository`, then `cozyDistributionDir`)
- `cozyWarehouseMavenCoordinates`: Maven coordinates indexed from `warehouse/maven`
- `cozyWarehouseRepositoryArtifacts`: repository artifact types indexed from warehouse, such as `car` and `sar`
- `cozyWarehouseRepositoryModules`: repository artifact module names indexed from warehouse

Tasks:

- `cozyBuildCAR`: build a CAR archive
- `cozyBuildSAR`: build a SAR archive
- `cozyPublishCAR`: copy the CAR built by `cozyBuildCAR` into the development local repository
- `cozyPublishSAR`: copy the SAR built by `cozyBuildSAR` into the development local repository
- `cozyDistributeCAR`: copy the CAR built by `cozyBuildCAR` into the release distribution repository
- `cozyDistributeSAR`: copy the SAR built by `cozyBuildSAR` into the release distribution repository
- `cozyDistribute`: distribute CAR or SAR based on `cozyPackaging`
- `cozyIndexWarehouse`: generate Maven/CAR/SAR release metadata in `publish.d` by reading the warehouse

## Backend Modes

`cozy` backend (default):

- `sbt-cozy` invokes `cozy sbt-bridge v1 --request=...`
- `sbt-cozy` does not call `modeler-scala` / `package-car` / `package-sar` directly
- the bridge request is the stable integration contract
- when `cozyDelegateProjectDir` is set, `sbt-cozy` runs `runMain cozy.Cozy` in that repo instead of the installed `cozy` command

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
resolvers += Resolver.defaultLocal
resolvers += Resolver.mavenLocal

addSbtPlugin("org.goldenport" % "sbt-cozy" % "0.1.5-SNAPSHOT")
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
sbt cozyBuildCAR
sbt cozyBuildSAR
```

Publish CAR/SAR into the local cozy repository:

```bash
sbt cozyPublishCAR
sbt cozyPublishSAR
```

Distribute release CAR/SAR archives into the configured release repository:

```bash
sbt cozyDistributeCAR
sbt cozyDistributeSAR
sbt cozyDistribute
```

`cozyDistribute*` rejects `SNAPSHOT` versions by default. Use it for release distribution only; keep `cozyPublishCAR/SAR` for development-local publication.

Generate BoK publication sources from the sbt project:

```bash
sbt cozyPublishProject
```

Generate BoK artifact/release metadata from the warehouse:

```bash
sbt cozyIndexWarehouse
```

Project-local defaults can be written in `.cozy/config.yaml`. The same values can also be written as sbt settings in `build.sbt`; explicit `build.sbt` settings take precedence over `.cozy/config.yaml`.

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

- normal use: install/update the `cozy` command and let `sbt-cozy` call it directly
- development against an in-progress `cozy` repo: set `cozyDelegateProjectDir := Some(file("/path/to/cozy"))`
- development against a published local SNAPSHOT: run `sbt publishLocal` in `cozy`, then set `cozyDelegateCoursierVersion := Some("0.2.17-SNAPSHOT")` or `SBT_COZY_COURSIER_VERSION=0.2.17-SNAPSHOT`

The default bridge route always launches the shell command `cozy` and passes the `sbt-bridge` subcommand to it. The coursier route is a development route and requires an explicit `cozy` version.

Bridge governance:

- vendored bridge fixtures live under `bridge-fixtures/sbt-bridge/v1/`
- canonical fixtures are sourced from `cozy/bridge/sbt-bridge/v1/`
- `scripts/check-bridge-fixtures.sh` detects fixture drift
- `scripts/check-version-bump.sh` enforces a plugin version bump when bridge fixtures change
- `scripts/check-bridge-compat.sh` runs the drift check, version-bump check, and test suite
