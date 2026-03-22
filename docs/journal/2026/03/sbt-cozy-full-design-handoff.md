Cozy Build System (sbt-cozy) — Unified Design (Handoff)
=====================================================

status=proposed
phase=7+
date=2026-03-21

---

# 1. Overview

This document defines the unified build system for CozyTextus:

```
sbt-cozy
```

It provides:

- CML modeling
- code generation
- packaging (CAR / SAR)
- dependency resolution
- publishing (Maven-style)
- CLI integration

---

# 2. Positioning

```
CozyTextus Platform

  Cozy       → DSL
  sbt-cozy   → build system
  CNCF       → runtime
  Textus     → component library
```

---

# 3. Core Concept

```
Model → Build → Package → Publish → Run
```

---

# 4. Responsibilities

## sbt-cozy MUST handle:

- CML parsing
- AST generation
- Scala code generation
- CAR packaging
- SAR packaging
- manifest generation
- publish to repository

---

## sbt-cozy MUST NOT handle:

- runtime execution
- orchestration
- cluster management

---

# 5. Artifact Model

## Component

```
CAR (Component Archive)
```

---

## Subsystem

```
SAR (Subsystem Archive)
```

---

## Coordinates

```
CAR: org.simplemodeling.car:<name>:<version>
SAR: org.simplemodeling.sar:<name>:<version>
```

---

# 6. Directory Layout

```
src/main/cozy/
  *.cml

src/main/scala/
  custom implementation

target/
  src_managed/
  *.car
  *.sar
```

---

# 7. Build Pipeline

## Component (CAR)

```
cozyGenerate
  → compile
  → cozyBuildCAR
  → cozyPublishCAR
```

---

## Subsystem (SAR)

```
cozyGenerate
  → cozyBuildSAR
  → cozyPublishSAR
```

---

# 8. SBT Plugin Structure

## AutoPlugin

```scala
object CozyPlugin extends AutoPlugin
```

---

## Keys

```scala
cozyGenerate      : Task[Seq[File]]

cozyBuildCAR      : Task[File]
cozyBuildSAR      : Task[File]

cozyPublishCAR    : Task[Unit]
cozyPublishSAR    : Task[Unit]

cozyPackaging     : Setting[String] // "car" or "sar"
```

---

# 9. Packaging Rules

## CAR

```
/component/
  main.jar

/spi/
  *.jar

/config/
  default.conf

/docs/
  *.md

/meta/
  manifest.json
```

---

## SAR

```
/subsystem/
  *.cml

/extension/
  *.jar

/config/
  application.conf

/meta/
  manifest.json
```

---

# 10. Extension Model

## CAR

- built-in extensions

---

## SAR

- injected extensions

---

## Merge Rule

```
SAR > CAR
```

---

# 11. CLI Integration

Future CLI:

```
cozy build
cozy publish
cozy run
```

---

## Mapping

| CLI | sbt |
|-----|-----|
| cozy build | cozyBuildCAR / SAR |
| cozy publish | cozyPublishCAR / SAR |
| cozy run | cncf run |

---

# 12. Example Workflow

## Component

```
sbt cozyBuildCAR
sbt cozyPublishCAR
```

---

## Subsystem

```
sbt cozyBuildSAR
sbt cozyPublishSAR
```

---

## Execution

```
cncf run org.simplemodeling.car:textus-user-account:0.1.0

cncf run org.simplemodeling.sar:textus-identity:0.1.0
```

---

# 13. Multi-Module Strategy

```
root
  ├─ component-user-account (CAR)
  ├─ component-session (CAR)
  ├─ subsystem-identity (SAR)
```

---

# 14. Naming Strategy

## Plugin

```
sbt-cozy
```

---

## CLI

```
cozy
```

---

## Artifacts

```
textus-user-account.car
textus-identity.sar
```

---

# 15. Design Principles

1. Model-first (CML)
2. Component as unit of reuse
3. Subsystem as unit of composition
4. Extension is pluggable
5. Build is deterministic

---

# 16. Constraints

- CAR must contain executable logic
- SAR must not contain core logic
- extension must bind via ExtensionPoint
- build output must be reproducible

---

# 17. Future Extensions

- incremental build
- dependency graph validation
- plugin ecosystem
- remote repository integration
- build caching

---

# 18. Key Definition

```
sbt-cozy = CozyTextus build system
```

---

End.
