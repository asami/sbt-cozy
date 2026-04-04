# sbt-cozy

`sbt-cozy` is an `sbt` plugin that generates Scala sources from CML files under `src/main/cozy` and builds CAR/SAR archives.

## Features

- Load `.cml`, `.cozy`, and `.dox` files
- Generate Scala sources via `cozy`'s `modeler-scala` backend by default
- Build CAR (`.car`) and SAR (`.sar`) archives
- Write `manifest.json` under `meta/`
- Publish CAR/SAR artifacts into a local repository with `cozyPublishCAR` and `cozyPublishSAR`

## Plugin Keys

### Generation

- `cozyConfig`: generation settings (`CozyConfig()` by default)
- `cozySourceDir`: input directory (`src/main/cozy` by default)
- `cozyTargetDir`: generation target (`Compile / sourceManaged` by default)
- `cozyGeneratorBackend`: generator backend (`cozy` or `legacy`, default: `cozy`)
- `cozyDelegateProjectDir`: optional path to the delegated `cozy` project
- `cozyDelegateCommand`: command prefix used for delegated generation (default: `Seq("sbt", "--batch")`)
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
- `cozyCarName`: base file name of the generated CAR archive (default: `${moduleName}-${version}`)
- `cozySarName`: base file name of the generated SAR archive (default: `${moduleName}-${version}`)
- `cozySpiJars`: additional JARs included under `spi/` in CAR
- `cozySarExtensionJars`: additional JARs included under `extension/` in SAR
- `cozyManifestMetadata`: extra metadata entries written into `manifest.json`
- `cozyLocalRepositoryDir`: destination directory for `cozyPublish*` tasks (default: `target/cozy-repository`)

Tasks:

- `cozyBuildCAR`: build a CAR archive
- `cozyBuildSAR`: build a SAR archive
- `cozyPublishCAR`: copy the CAR built by `cozyBuildCAR` into the local repository
- `cozyPublishSAR`: copy the SAR built by `cozyBuildSAR` into the local repository

## Backend Modes

`cozy` backend (default):

- `cozyGenerate` runs `cozy.Cozy modeler-scala` and copies generated Scala sources into `cozyTargetDir`
- if `cozyDelegateProjectDir` is not set, `sbt-cozy` first checks `SBT_COZY_PROJECT_DIR`
- if that is also unset, it searches same-workspace candidates first: `../cozy`, `../cncf/cozy`, `../modules/cozy`, `../tools/cozy`
- the older `~/src/dev20xx/cozy` lookup remains as a backward-compatible fallback

`legacy` backend:

- uses the built-in `sbt-cozy` parser and generator
- switch with `cozyGeneratorBackend := "legacy"`

## Artifact Layout

### CAR

- `component/main.jar`
- `lib/*.jar`
- `spi/*.jar`
- `config/default.conf`
- `docs/*.md`
- `meta/manifest.json`

### SAR

- `subsystem/*.cml` with original relative paths preserved
- `extension/*.jar`
- `config/application.conf`
- `meta/manifest.json`

Precedence contract in `manifest.json`:

- extension: `SAR > CAR`
- config: `SAR > CAR`

## Usage

`project/plugins.sbt`:

```scala
resolvers += Resolver.defaultLocal
resolvers += Resolver.mavenLocal

addSbtPlugin("org.goldenport" % "sbt-cozy" % "0.1.2")
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

If `sbt-cozy` is placed in the same workspace as `cozy`, the plugin resolves the local `cozy` module automatically without additional configuration.
