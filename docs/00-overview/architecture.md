# Architecture

## Purpose and lifecycle

Public Java release projection; canonical source is in NPS-Dev.

## Boundaries

src/main/: production.

src/test/: tests.

gradle/: wrapper.

spec/: released snapshot.

doc/: documentation.

Keep responsibilities and dependency direction explicit. Do not add layers or cross-repository sources of truth without a concrete requirement. Record architecture-significant decisions under `docs/04-decisions/`.
