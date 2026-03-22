# sbt-cozy

`src/main/cozy` の CML から Scala ソース生成と CAR/SAR パッケージングを行う `sbt` プラグインです。

## Features

- `.cml` / `.cozy` / `.dox` を読み込み
- CML 定義を AST 化して Scala ソース生成
- CAR (`.car`) / SAR (`.sar`) アーカイブ生成
- `manifest.json` を `meta/` に出力
- `cozyPublishCAR` / `cozyPublishSAR` でローカルリポジトリへ配置

## Plugin Keys

### Generation

- `cozyConfig`: 生成設定（default: `CozyConfig()`）
- `cozySourceDir`: 入力ディレクトリ（default: `src/main/cozy`）
- `cozyTargetDir`: 生成先（default: `Compile / sourceManaged`）
- `cozyGenerate`: 生成タスク

`cozyConfig` options:

- `generateDerivedAggregates`: `entity` から `<Entity>Aggregate` を自動生成（default: `true`）
- `generateDerivedViews`: `entity` から `<Entity>View` を自動生成（default: `true`）
- `packagePrefix`: CML の `package` へ付与する prefix（default: `None`）

### Packaging

- `cozyPackaging`: 既定パッケージング種別（`car` / `sar`）
- `cozyCarName`: CAR ファイル名のベース（default: `${moduleName}-${version}`）
- `cozySarName`: SAR ファイル名のベース（default: `${moduleName}-${version}`）
- `cozySpiJars`: CAR の `spi/` に入れる追加 JAR
- `cozySarExtensionJars`: SAR の `extension/` に入れる追加 JAR
- `cozyManifestMetadata`: `manifest.json` へ追記する任意メタ情報
- `cozyLocalRepositoryDir`: `cozyPublish*` の配置先（default: `target/cozy-repository`）

Tasks:

- `cozyBuildCAR`: CAR 生成
- `cozyBuildSAR`: SAR 生成
- `cozyPublishCAR`: `cozyBuildCAR` の出力をローカルリポジトリへコピー
- `cozyPublishSAR`: `cozyBuildSAR` の出力をローカルリポジトリへコピー

## CML minimal syntax

```txt
package com.example.cozy

entity UserAccount
command CreateUser
query GetUser
operation CreateUserOp command CreateUser
```

Available declarations:

- `package <name>`
- `entity <Name>`
- `aggregate <Name>`
- `view <Name>`
- `command <Name>`
- `query <Name>`
- `event <Name>`
- `operation <Name>`
- `operation <Name> command <CommandName>`
- `operation <Name> query <QueryName>`
- `operation <Name> uses command <CommandName>`
- `operation <Name> uses query <QueryName>`

## Artifact layout

### CAR

- `component/main.jar`
- `lib/*.jar`
- `spi/*.jar`
- `config/default.conf`
- `docs/*.md`
- `meta/manifest.json`

### SAR

- `subsystem/*.cml`（入力 CML を相対パス維持で格納）
- `extension/*.jar`
- `config/application.conf`
- `meta/manifest.json`

Precedence contract (`manifest.json`):

- extension: `SAR > CAR`
- config: `SAR > CAR`

## Usage

`project/plugins.sbt`:

```scala
addSbtPlugin("org.goldenport" % "sbt-cozy" % "0.1.0-SNAPSHOT")
```

`build.sbt`:

```scala
enablePlugins(org.goldenport.cozy.CozyPlugin)
```

Generate only:

```bash
sbt cozyGenerate
```

Build archives:

```bash
sbt cozyBuildCAR
sbt cozyBuildSAR
```

Publish to local cozy repository:

```bash
sbt cozyPublishCAR
sbt cozyPublishSAR
```

Example settings:

```scala
import org.goldenport.cozy.CozyConfig

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
