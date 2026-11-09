# Phase 1 checklist — Strict Scala 3 compiler and build qualification

status=planned

Plan: [Phase 1](phase-1.md).
Owner: sbt-cozy development.
The original delivery IDs and criteria are preserved. All items remain OPEN.

## Stage: Producer delivery

Stage Status:
- Current status: OPEN
- Owner: sbt-cozy development
- Update rule: Accept actual compiler/build behavior; a plan is not availability.
- Closure basis: BUILD-01–BUILD-05 below; no new plugin feature is assumed.

- [ ] BUILD-01: Inventory actual build settings and rule-resource/task wiring.
      Separate existing capability from a demonstrated gap; any plugin change
      must be tied to that gap within this delivery.
- [ ] BUILD-02: Qualify the selected strict policy on the actual Scala 3 target,
      starting with 3.3.8: strictEquality, explicit nulls, initialization safety,
      discarded/non-Unit results, applicable unused warnings, unchecked,
      deprecation/feature warnings and warning-as-error treatment. Record exact
      supported option names and source scopes; do not copy newer-version flags
      blindly or disable warnings broadly to pass.
- [ ] BUILD-03: Qualify a small ordinary project with and without sbt-cozy for
      the applicable lint route, handwritten Compile/Test selection and managed
      source exclusion. Missing semantic analysis or selected rules is an explicit
      failure, never a clean result. Generated code is qualified by compilation
      and generator specifications, not relabeled as handwritten lint input.
      Qualify the structural-braces/terminal-expression rule through the same
      check route. Any selected Scalafmt settings must retain structural braces;
      blanket optional-brace removal or global disabling of allowed leaf
      indentation must not contradict the policy. Formatting success alone is
      not structural-rule acceptance.
- [ ] BUILD-04: Consume accepted SimpleModeler/Cozy output through ordinary
      generation and compile in a tiny isolated target; use actual supported
      compiler policy and demonstrate scoped exceptions. No product copy,
      self-publication loop, mandatory scripted run or global SBT wrapper lock.
- [ ] BUILD-05: Return SLP-BUILD with actual settings, versions, source scopes,
      task/config references, retained results/review and whether a plugin change
      or ordinary publication is actually required.
