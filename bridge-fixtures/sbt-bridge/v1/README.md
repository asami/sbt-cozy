# sbt-bridge v1

This directory is the canonical source of truth for the `cozy sbt-bridge v1`
contract consumed by `sbt-cozy`.

Contract rules:
- protocol: `sbt-bridge`
- version: `v1`
- supported actions: `generate`, `package-car`, `package-sar`
- additive request fields are allowed only if existing consumers can ignore them
- removals, renames, or semantic changes require `v2`

Request contract:
- JSON file passed by `--request=<file>`
- required fields:
  - `version`
  - `action`
  - `arguments`
- optional fields:
  - `settings`

Response contract in `v1`:
- runtime success/failure is process-oriented
- success is signaled by process exit `0`
- failure is signaled by non-zero exit and diagnostic text
- `response-*.json` files in this directory are canonical compatibility envelopes for tooling and future structured-response evolution; they are not emitted on stdout by the current runtime
