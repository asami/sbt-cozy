sbt-cozy Plugin Design (Handoff)
=======================================

status=proposed
date=2026-03-21

---

# 1. Overview

This document defines the SBT plugin:

```
sbt-cozy
```

The plugin provides:

- CML (Cozy Modeling Language) processing
- code generation from `src/main/cozy`
- integration with SBT build lifecycle

---

# 2. Purpose

The plugin enables:

```
CML → AST → Scala code generation
```

It bridges:

- modeling (CML)
- implementation (Scala)
- CNCF runtime

---

# 3. Naming Decision

Plugin name:

```
sbt-cozy
```

Rationale:

- "cozy" = DSL system
- "codegen" = primary function
- aligns with sbt plugin naming conventions

---

# 4. Scope

## Included

- parse CML files
- build AST
- generate Scala sources
- output to `target/src_managed`

## Excluded (initial)

- runtime execution
- CLI tooling
- validation beyond syntax

---

# 5. Directory Structure

```
src/main/cozy/
  *.cml (or .dox / cozy format)

target/scala-*/src_managed/
  entity/
  aggregate/
  view/
  command/
  query/
  operation/
```

---

# 6. Build Integration

## Key Pipeline

```
compile
  └─ cozyGenerate
        ├─ parse CML
        ├─ build AST
        └─ generate Scala
```

---

## SBT Task

```
cozyGenerate
```

---

## Integration

```
Compile / sourceGenerators += cozyGenerate.taskValue
```

---

# 7. Plugin Structure

## AutoPlugin

```scala
object CozyPlugin extends AutoPlugin
```

---

## Keys

```scala
val cozySourceDir   = settingKey[File]
val cozyTargetDir   = settingKey[File]
val cozyGenerate    = taskKey[Seq[File]]
```

---

## Defaults

```scala
cozySourceDir := (Compile / sourceDirectory).value / "cozy"
cozyTargetDir := (Compile / sourceManaged).value
```

---

# 8. Processing Flow

## Step 1: Load

```
src/main/cozy → load files
```

---

## Step 2: Parse

```
CML → AST
```

AST includes:

- EntityDef
- ValueDef (Command / Query)
- OperationDef
- EventDef

---

## Step 3: Normalize

```
Operation:
  (b) parameter form
    → canonical (a) input form
```

---

## Step 4: Generate

Generate:

- entity classes
- aggregate classes
- view classes
- command/query values
- operation definitions

---

# 9. Code Generation Targets

## Entity

```
entity/UserAccount.scala
```

---

## Aggregate

```
aggregate/UserAccountAggregate.scala
```

---

## View

```
view/UserAccountView.scala
```

---

## Command / Query

```
command/CreateUser.scala
query/GetUser.scala
```

---

## Operation

```
operation/CreateUserOp.scala
```

---

# 10. Incremental Strategy

Initial implementation:

```
always regenerate all files
```

Future:

```
- timestamp-based
- hash-based incremental generation
```

---

# 11. Error Handling

## Compile Failure

- parsing errors → fail build
- invalid references → fail build

---

## Messages

- clear file/line location
- minimal noise

---

# 12. Extension Points

Future extension areas:

- validation rules
- schema versioning
- plugin configuration
- multi-module support

---

# 13. Configuration (future)

```
cozyConfig := CozyConfig(...)
```

Examples:

- enable/disable aggregate generation
- naming conventions
- package prefix

---

# 14. Constraints

- generation must be deterministic
- no runtime side effects
- output must be idempotent

---

# 15. Relation to CNCF

This plugin is foundational for:

- Phase 7 (Aggregate/View)
- Operation DSL
- Event/Job execution model

---

# 16. Implementation Plan

## Step 1

Create plugin skeleton

---

## Step 2

Implement file loader

---

## Step 3

Implement parser (CML → AST)

---

## Step 4

Implement generator

---

## Step 5

Integrate with SBT compile

---

# 17. Key Definition

```
sbt-cozy = CML → Scala code generation pipeline
```

---

End.
