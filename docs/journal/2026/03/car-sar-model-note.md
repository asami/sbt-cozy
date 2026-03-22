CAR / SAR Distribution and Extension Model
========================================

status=proposed
date=2026-03-21

---

# 1. Overview

This document defines the distribution and runtime model of:

- CAR (Component Archive)
- SAR (Subsystem Archive)

These two artifacts establish the separation between:

- implementation (Component)
- composition (Subsystem)

and introduce a dual extension model.

---

# 2. Core Definitions

## CAR (Component Archive)

```
CAR = Component distribution artifact
```

Contains:

- component implementation
- componentlets
- built-in extensions (SPI)
- default configuration
- documentation

---

## SAR (Subsystem Archive)

```
SAR = Subsystem composition artifact
```

Contains:

- subsystem wiring (CML)
- component dependency declaration
- configuration override
- injected extensions (external plugins)

---

# 3. Responsibility Separation

| Concern | CAR | SAR |
|--------|-----|-----|
| Implementation | ✔ | ✘ |
| Composition | ✘ | ✔ |
| Built-in Extension | ✔ | ✘ |
| Injected Extension | ✘ | ✔ |
| Configuration | default | override |
| Execution Unit | ✔ | ✘ |

---

# 4. Artifact Structure

## CAR

```
<component>.car

  /component/
    main.jar

  /lib/
    *.jar

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
<subsystem>.sar

  /subsystem/
    subsystem.cml

  /extension/
    *.jar

  /config/
    application.conf

  /meta/
    manifest.json
```

---

# 5. Extension Model

## 5.1 Dual Source Extension

Extensions can originate from:

```
1. CAR (built-in)
2. SAR (injected)
```

---

## 5.2 Purpose

### CAR Extensions

- default capabilities
- internal or standard features

Examples:

- password hash strategies
- validation modules

---

### SAR Extensions

- environment-specific behavior
- external integration

Examples:

- gRPC transport driver
- Kafka adapter
- custom authentication provider

---

# 6. Extension Binding

Extensions are bound via ExtensionPoints.

## Component defines:

```
ExtensionPoint:
  transport
  password-hash
```

---

## SAR selects:

```
transport = grpc
password-hash = bcrypt
```

---

## Resolution

```
SAR extension > CAR extension
```

---

# 7. Runtime Flow

```
1. Load SAR
2. Load SAR extension jars
3. Resolve Component dependencies (CAR)
4. Load CAR
5. Load CAR extensions
6. Merge extension registry
7. Apply configuration
8. Apply wiring (Subsystem)
9. Execute
```

---

# 8. ClassLoader Model

```
[Extension ClassLoader]
  ├─ SAR extensions
  └─ CAR extensions

[Component ClassLoader]
  └─ Component implementation
```

Isolation rules:

- extension must not directly mutate component
- communication via ExtensionPoint only

---

# 9. Configuration Model

## CAR

```
default configuration
```

---

## SAR

```
override / environment configuration
```

---

## Merge Order

```
SAR config > CAR config
```

---

# 10. Distribution Model

## CAR

```
org.simplemodeling.car:<component>:<version>
```

---

## SAR

```
org.simplemodeling.sar:<subsystem>:<version>
```

---

## Example

```
org.simplemodeling.car:textus-user-account:0.1.0
org.simplemodeling.sar:textus-identity:0.1.0
```

---

# 11. Execution Model

## Component

```
cncf run org.simplemodeling.car:...
```

---

## Subsystem

```
cncf run org.simplemodeling.sar:...
```

---

# 12. Design Principles

1. Strict separation of implementation and composition
2. Component is the unit of reuse
3. Subsystem is the unit of configuration
4. Extensions are pluggable and optional
5. Environment-specific logic belongs to SAR

---

# 13. Constraints

- SAR must not contain core business logic
- CAR must not depend on SAR
- Extensions must bind only via ExtensionPoints
- Component must remain functional without SAR extensions

---

# 14. Key Insight

```
CAR provides capability
SAR defines how to use that capability
```

---

# 15. Summary

```
CAR = implementation + built-in extensions
SAR = composition + injected extensions + configuration
```

---

End.
