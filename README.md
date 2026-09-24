# EnthusiaFrontier

EnthusiaFrontier is a Leaf/Paper world-frontier manager for large SMP worlds. It separates the **usable world border** from the **pregenerated permanent core**, allowing Enthusia to expose much more land without pregenerating and permanently storing the entire expanded square.

The plugin has two independent safety systems:

1. **Adaptive generation throttling** — watches MSPT and changes Paper/Moonrise per-player generation limits before exploration pressure can materially damage tick performance.
2. **Frontier lifecycle management** — tracks newly generated chunks outside the permanent core, permanently protects chunks with real player activity, and can reclaim untouched frontier terrain after a retention period.

For production Enthusia the intended permanent overworld core is `100000` blocks in each horizontal direction. For disposable testing, `core-radius-blocks: 0` means **the entire world is managed by Frontier**.

## Cleanup safety

Destructive cleanup is implemented but intentionally **disabled by default**. Production rollout is expected to progress through tracking, dry-run review, disposable real-server acceptance, and only then explicit operator enablement.

The cleanup path is fail-closed:

- activity protection is monotonic and is installed in memory before its async SQLite mutation;
- SQLite commits a durable reclaim intent before Moonrise storage is modified;
- interrupted reclaim intents survive restart and are recovered before new candidates are reserved;
- queue/database/compatibility failures trip a persistent cleanup safety latch;
- logical removal clears chunk, entity and POI data through Moonrise's synchronized storage path;
- physical `.mca` unlink happens only for a fully empty region that Moonrise no longer has open;
- unknown or alternative storage formats are retained rather than guessed safe.

Read:

- [`docs/SPECIFICATION.md`](docs/SPECIFICATION.md) — functional and safety requirements.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — ports/adapters design and destructive-state ordering.
- [`docs/TESTING.md`](docs/TESTING.md) — CI, Sentinel Sim and real-server acceptance strategy.
- [`docs/sentinel-staging.md`](docs/sentinel-staging.md) — exact-head Sentinel/Staging integration.

## Platform baseline

- Java 21
- Minecraft 1.21.11
- Paper API `1.21.11-R0.1-SNAPSHOT`
- Leaf as the production Paper-compatible runtime

## Quality policy

Every pull request must compile with `-Xlint:all -Werror`, pass JUnit/integration tests, SpotBugs at max effort, the 70% JaCoCo gate over the independently unit-testable core/persistence surface, and build the exact deployable shaded JAR. Real Paper/Moonrise adapters that cannot be honestly executed in JVM unit tests are still compiled and statically analyzed and must pass the isolated real-server acceptance profile.

The repository publishes a stable exact-head `sentinel-plugin/plugin.jar` artifact for Enthusia Sentinel. Static analysis is evidence, not a substitute for real-server validation.
