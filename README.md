# EnthusiaFrontier

EnthusiaFrontier is a Leaf/Paper world-frontier manager for large SMP worlds. It separates the **usable world border** from the **pregenerated permanent core**, allowing Enthusia to expose much more land without pregenerating and permanently storing the entire expanded square.

The runtime has three related safety layers:

1. **Global Generation Shield** — Frontier owns one bounded, requester-fair queue for explicit/fail-safe virgin terrain generation. Rate and concurrency budgets apply once to the whole server, and MSPT can reduce that budget all the way to a true `0` new admissions.
2. **Paper/Moonrise generation throttle** — Paper's existing per-player rate/concurrency controls are adjusted from measured MSPT. Healthy live play can run without an artificial player generation cap; as tick time rises Frontier progressively reduces generation pressure.
3. **Frontier lifecycle management** — newly generated chunks outside the permanent core are tracked, meaningful player activity permanently protects terrain, and untouched frontier terrain can become reclaimable after the retention period.

During healthy live play, Frontier does **not** cancel normal walking, Elytra/creative flight, vehicles, portals, or teleports just because a full view-distance safety square has not been pregenerated. Paper performs normal chunk loading while Frontier monitors MSPT and adapts its generation throttle. If the global generation shield is paused or unhealthy, Frontier switches to a fail-closed emergency movement mode: a proven-ready buffer based on runtime view distance, send-view distance, simulation distance, and the configured safety margin must be ready before further managed-frontier advancement.

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
- [`docs/TESTING.md`](docs/TESTING.md) — CI, real-Leaf generation/load validation, Sentinel and destructive acceptance strategy.
- [`docs/sentinel-staging.md`](docs/sentinel-staging.md) — exact-head Sentinel/Staging integration and validation boundaries.

## Platform baseline

- Java 21
- Minecraft 1.21.11
- Paper API `1.21.11-R0.1-SNAPSHOT`
- Leaf as the production Paper-compatible runtime

## Quality and runtime evidence

Every pull request must compile with `-Xlint:all -Werror`, pass JUnit/integration tests, SpotBugs at max effort, the 70% JaCoCo gate over the independently unit-testable core/persistence surface, and build the exact deployable shaded JAR. Real Paper/Moonrise adapters that cannot be honestly executed in JVM unit tests are still compiled and statically analyzed and must pass isolated real-server acceptance.

The server-runtime workflow also rebuilds the deployable JAR twice and requires byte-for-byte reproducibility before it can publish. The exact JAR is then booted on Leaf 1.21.11, driven through a real async-generation 1/10/20/40 synthetic-requester suite, shut down cleanly, and restarted against the same SQLite/world state. This proves the server-global admission budget and real Leaf adapter/readiness path; it is **not** a substitute for real-client staging or production-hardware calibration.

The repository also publishes a stable exact-head `sentinel-plugin/plugin.jar` artifact for Enthusia Sentinel. Static analysis and synthetic requesters are evidence, not substitutes for real-player validation.