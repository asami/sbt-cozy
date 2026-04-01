# sbt-cozy

`src/main/cozy` の CML から Scala ソース生成と CAR/SAR パッケージングを行う `sbt` プラグインです。

## Features

- `.cml` / `.cozy` / `.dox` を読み込み
- `cozy` の `modeler-scala` に委譲して Scala ソース生成（default backend）
- CAR (`.car`) / SAR (`.sar`) アーカイブ生成
- `manifest.json` を `meta/` に出力
- `cozyPublishCAR` / `cozyPublishSAR` でローカルリポジトリへ配置

## Plugin Keys

### Generation

- `cozyConfig`: 生成設定（default: `CozyConfig()`）
- `cozySourceDir`: 入力ディレクトリ（default: `src/main/cozy`）
- `cozyTargetDir`: 生成先（default: `Compile / sourceManaged`）
- `cozyGeneratorBackend`: 生成バックエンド（`cozy` / `legacy`, default: `cozy`）
- `cozyDelegateProjectDir`: 委譲先 `cozy` プロジェクトディレクトリ（`Option[File]`）
- `cozyDelegateCommand`: 委譲時の実行コマンドプレフィックス（default: `Seq("sbt", "--batch")`）
- `cozySkipUnchangedGeneration`: CML のタイムスタンプと生成設定が変わらない場合は再生成を抑止（default: `true`）
- `cozyGenerate`: 生成タスク

`cozyConfig` options:

- `generateDerivedAggregates`: `entity` から `<Entity>Aggregate` を自動生成（default: `true`）
- `generateDerivedViews`: `entity` から `<Entity>View` を自動生成（default: `true`）
- `packagePrefix`: CML の `package` へ付与する prefix（default: `None`）

`cozyConfig` は `legacy` バックエンド専用です。`cozy` バックエンドでは `cozy` 側の生成設定に従います。

`cozySkipUnchangedGeneration := true` の場合、`target/sbt-cozy/generation-state.properties` に入力 CML の相対パスとタイムスタンプ、backend、生成設定を記録し、差分がなければ既存 Scala を再利用します。

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

## Backend mode

`cozy` バックエンド（default）:

- `cozyGenerate` は `cozy.Cozy modeler-scala` を実行し、生成済みScalaを `cozyTargetDir` へコピーする
- `cozyDelegateProjectDir` 未指定時は環境変数 `SBT_COZY_PROJECT_DIR`、または既知候補ディレクトリを探索する

`legacy` バックエンド:

- 従来の `sbt-cozy` 内蔵パーサ/ジェネレータを使用する（互換用）
- `cozyGeneratorBackend := "legacy"` で切替

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
