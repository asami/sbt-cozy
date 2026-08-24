# Component Source Archive Projection

This scripted fixture is an ordinary non-CAR sbt project. It applies
`CozyPlugin` only to invoke `cozySourceArchive`; it intentionally has no
`project.yaml` and does not exercise generation or CAR packaging.

The fixture verifies `target/cozy/component-source-archive.json` as canonical
`cozy.component-source-archive.v1` inventory evidence. It parses the JSON,
checks the sorted source and root-document entry digests, excludes target and
host state, and confirms byte-identical output after `clean`.
