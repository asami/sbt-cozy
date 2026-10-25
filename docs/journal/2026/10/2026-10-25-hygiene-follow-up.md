# Hygiene follow-up

## HYG-SBT-COZY-001 — configuration fixture work placement

- Status: OPEN
- Discovered: 2026-10-25, independent review before repository synchronization.
- Location: `src/test/scala/org/goldenport/cozy/CozyProjectConfigLayersSpec.scala`, `_with_project`.
- Category / priority: test artifact placement / low.
- Evidence: the configuration-only fixture uses a system temporary directory;
  the shared Work Artifact Placement Rules prescribe repository-owned
  `target/<tool>/work/` artifacts. Cleanup already runs in `finally`.
- Risk: temporary test work is harder to locate; no observed behavior failure
  or loss of validation integrity.
- Boundary: defer this independent fixture-placement maintenance from the
  admitted configuration/runtime commit and its GWT authoring repairs.
- Proposed task: allocate unique fixture children under the repository's
  target test-work directory while retaining existing cleanup.
- Task / commit: not scheduled.
