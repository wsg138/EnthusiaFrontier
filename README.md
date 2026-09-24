# EnthusiaFrontier

EnthusiaFrontier is a Leaf/Paper world-frontier manager for large SMP worlds. It separates the **usable world border** from the **pregenerated core** so Enthusia can expose far more land without pregenerating and permanently storing every possible chunk.

The plugin is being built around two independent safety systems:

1. **Adaptive generation throttling** — watches server MSPT and changes Paper/Moonrise per-player generation limits before exploration pressure can meaningfully damage tick performance.
2. **Frontier lifecycle tracking** — records newly generated chunks outside a configurable permanent core and permanently protects chunks where real player activity occurs. Untouched frontier terrain can later be reclaimed under strict, auditable safety rules.

For production Enthusia the intended permanent core is `100000` blocks in each horizontal direction. For disposable testing, `core-radius-blocks: 0` means **the entire world is managed by Frontier**.

## Project status

The initial implementation milestone covers the architecture, exact configuration contract, persistent SQLite activity ledger, bounded asynchronous mutation journal, persisted cleanup safety latch, and adaptive Paper generation-rate controller. Destructive chunk reclamation is deliberately gated behind later real-server acceptance work.

Read:

- [`docs/SPECIFICATION.md`](docs/SPECIFICATION.md) — complete functional and safety specification.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — hexagonal architecture and dependency boundaries.
- [`docs/TESTING.md`](docs/TESTING.md) — unit, Sentinel Sim, Enthusia Staging, and destructive acceptance strategy.

## Platform baseline

- Java 21
- Minecraft 1.21.11
- Paper API `1.21.11-R0.1-SNAPSHOT`
- Leaf supported as the production Paper-compatible runtime

## Quality policy

Every pull request must compile with `-Xlint:all -Werror`, pass unit/integration tests, SpotBugs, JaCoCo verification, and build the exact deployable shaded JAR. The repository publishes a stable `sentinel-plugin/plugin.jar` PR artifact for Enthusia Sentinel Sim.

A static-analysis grade is evidence, not a substitute for runtime validation. The project target is an A-grade Codacy result with zero unresolved high-confidence correctness/security findings on release heads.
