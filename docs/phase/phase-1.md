# Phase 1 — Strict Scala 3 compiler and build qualification

status=planned
execution_started=false

Purpose: Strict Scala 3 compiler and build qualification.
Progress: [Phase 1 checklist](phase-1-checklist.md).
This is the producer's own Phase; sm-workflow Phase 6.12 is its consumer.

Planned: 2026-11-09
Consumer: [sm-workflow 6.12-A/B/C](../../../sm-workflow/docs/phase/phase-6.12.md)
Coordination: [delivery dependencies](../../../sm-workflow/docs/phase/phase-6.12-dependencies.md)
Policy: [compiler and lint responsibility](../../../sm-workflow/docs/spec/scala-lint-profiles.md#three-stage-strict-scala-3-adoption)

## Scope

Deliver SLP-BUILD: qualify the ordinary build's compiler configuration, generated
source wiring and check-only Scalafix integration for the selected strict Scala 3
profile. Existing `Compile / sourceGenerators` and release evidence already use
ordinary SBT source/scalacOptions settings. Reuse those mechanisms first; return
a qualified no-change delivery if adequate. Do not invent a competing config
resolver or require sbt-cozy in every lint-only project.

The plugin host and analyzed/generated Scala version are separate. Strict Scala 3
options must not leak into the Scala 2 plugin host or SimpleModeler host. Keep
exact Scala/SBT/Scalafix/SemanticDB compatibility and Compile/Test/generated
source scopes explicit. sm-workflow owns effective baseline/team/project merge;
the plugin/build consumes it rather than maintaining another merge algorithm.

## Work and acceptance

The [Phase 1 checklist](phase-1-checklist.md) is the completion
ledger. Its existing delivery IDs and acceptance criteria are retained unchanged.

## Dependencies and early handoff

BUILD-01/02 can start from existing target/compiler settings independently of
generator and Core changes. BUILD-03 consumes the accepted shared-rule artifact
and effective configuration from sm-workflow, not application Phase completion.
BUILD-04 consumes only the bounded
[Cozy](../../../cozy/docs/phase/phase-89.md) /
[SimpleModeler](../../../simple-modeler/docs/phase/phase-1.md)
output subset. Hand off qualified compiler settings before final generated
integration so neither owner waits for the other's whole delivery.

Runtime-owned checks/publication remain CNCF typed SBT/UoW; independent SBT
uses its existing owner and sbt-native locks. This plan executes neither route,
changes no live build/configuration, and preserves Goals, Sessions, history,
repair counts, successful results and unrelated changes.
