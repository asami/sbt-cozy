CNCF CAR Distribution Model (Maven-style) (Handoff)
=================================================

status=proposed
date=2026-03-21

---

# 1. Overview

This document defines the distribution model for CNCF Component Archive (CAR).

CAR is distributed via Maven-style coordinates:

```
groupId / artifactId / version
```

Example:

```
org.simplemodeling.car:textus-user-account:0.1.0
```

---

# 2. Core Concept

```
CAR = Component distribution artifact
```

Distribution follows Maven conventions.

---

# 3. Coordinate Model

## Format

```
groupId:artifactId:version
```

---

## Example

```
org.simplemodeling.car:textus-user-account:0.1.0
```

---

## Naming Rules

### groupId

```
org.simplemodeling.car
```

- reserved for official distribution
- may be extended:

```
org.simplemodeling.car.textus
org.simplemodeling.car.community
```

---

### artifactId

```
textus-user-account
```

Pattern:

```
<domain>-<component>
```

---

### version

```
0.1.0
```

- semantic versioning (recommended)
- backward compatibility rules apply

---

# 4. Repository Layout

## Maven Repository

```
/org/simplemodeling/car/textus-user-account/0.1.0/
  textus-user-account-0.1.0.car
  textus-user-account-0.1.0.pom
```

---

## Optional metadata

```
maven-metadata.xml
```

---

# 5. CAR File

## Extension

```
.car
```

---

## Content

```
/component/
  classes / jars

/meta/
  manifest.json
```

---

## Manifest example

```json
{
  "name": "textus-user-account",
  "version": "0.1.0",
  "component": "textus-user-account",
  "dependencies": []
}
```

---

# 6. Dependency Model

CAR can declare dependencies:

```
org.simplemodeling.car:textus-credential:0.1.0
```

---

## Resolution

At runtime:

```
CAR loader
  → resolve dependencies
  → load transitively
```

---

# 7. CLI Usage

## Run component

```
cncf run org.simplemodeling.car:textus-user-account:0.1.0
```

---

## Fetch

```
cncf fetch org.simplemodeling.car:textus-user-account:0.1.0
```

---

# 8. Local Cache

Local repository:

```
~/.cncf/repository/
```

Structure:

```
/org/simplemodeling/car/textus-user-account/0.1.0/
  textus-user-account-0.1.0.car
```

---

# 9. Subsystem Integration

Subsystem is NOT distributed as CAR.

Subsystem is defined via:

```
textus-identity.cml
```

---

## Execution

```
cncf run --subsystem textus-identity.cml
```

---

## Resolution

Subsystem loads required Components via:

```
dependencies:
  org.simplemodeling.car:textus-user-account:0.1.0
```

---

# 10. Bundle (Optional)

For convenience:

```
textus-identity.bundle
```

Contains:

```
/component/
  *.car

/subsystem/
  textus-identity.cml
```

---

# 11. Versioning Strategy

- semantic versioning (MAJOR.MINOR.PATCH)
- backward compatibility required for MINOR/PATCH
- breaking changes → MAJOR

---

# 12. Security (future)

- signature verification
- checksum validation
- trusted repository

---

# 13. Design Rationale

## Why Maven-style

```
- standard ecosystem
- dependency resolution
- version management
- tooling compatibility
```

---

## Why CAR

```
- CNCF-specific packaging
- component boundary isolation
```

---

# 14. Key Definition

```
CAR = Maven-distributed Component artifact
```

---

End.
