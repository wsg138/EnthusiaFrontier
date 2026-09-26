# Testing Strategy

Frontier has three distinct risk surfaces: pure policy/persistence correctness, real Leaf/Paper generation/runtime behavior, and destructive Moonrise storage behavior. Validation is intentionally split so evidence from one layer is never overstated as proof of another.

## 1. Normal CI

Every PR and every push to `main` runs:

```text
gradle clean check shadowJar --no-daemon
```

Required gates:

- Java 21 compilation with `-Xlint:all -Werror`;
- JUnit domain/application/config tests;
- SQLite integration tests using temporary databases, including durable reclaim-intent restart recovery and exact-key reservation;
- Generation Shield tests proving aggregate rate/concurrency, 40-requester non-multiplication, global dedupe, fairness, bounded queue behavior, true zero admission, durable-readiness slot retention and fail-closed failures;
- MCA header/negative-coordinate/sidecar tests;
- SpotBugs at max effort / low-confidence reporting with failures enforced;
- 70% JaCoCo line coverage over the independently unit-testable core/persistence surface;
- reproducible shaded deployable JAR;
- production platform-baseline guard.

Real Bukkit/Paper/Moonrise runtime adapters are not counted in the JaCoCo denominator because JVM tests cannot execute their real contract honestly. They are still compiled with warnings-as-errors, analyzed by SpotBugs and exercised by isolated real-server workflows.

## 2. Exact Frontier runtime on Leaf 1.21.11

The `Publish Frontier server runtime binaries` workflow is a publication gate, not merely a packaging job.

Before it may publish the Frontier runtime JAR it:

1. builds `clean check shadowJar`;
2. saves the exact JAR SHA-256;
3. rebuilds the same source from clean outputs;
4. requires the second JAR to be byte-for-byte identical;
5. downloads the pinned Leaf 1.21.11-179 runtime and verifies its pinned SHA-256;
6. boots the exact produced Frontier JAR on that real Leaf runtime;
7. runs `/frontier status` and requires the global Paper-async Generation Shield to be active;
8. runs the isolated 1/10/20/40 generation-admission suite described below;
9. stops Leaf cleanly;
10. verifies SQLite integrity and durable generated rows;
11. restarts the **same** world/plugin state and again requires clean enable/status/shutdown;
12. verifies the same durable generated rows still exist and no safety latch appeared;
13. only then uploads/publishes the runtime binary and source/hash manifest.

The workflow uploads quality reports, exact binary hashes and bounded Leaf logs as evidence.

## 3. Real-Leaf 1/10/20/40 aggregate generation proof

`FrontierGenerationLoadHarness` is fenced to the disposable loopback Sentinel-style server and is driven only from server console with the exact confirmation token.

Each case submits 48 unique virgin managed chunks through the **production** `GenerationShieldService`, the production `PaperChunkGenerationAdapter`, and the durable `SqliteGenerationReadinessAdapter`. The requests are distributed across 1, 10, 20 or 40 synthetic requester IDs.

The suite requires:

- the shield starts healthy and idle;
- every initial request is queued rather than bypassed/already ready;
- peak global in-flight generation never exceeds the configured whole-server upper bound;
- elapsed admission time cannot be faster than the configured whole-server rate (with a small scheduler tolerance), so requester count cannot multiply throughput;
- all requested chunks complete and become durable before the case finishes;
- queue depth/in-flight state returns to zero between cases;
- the shield stays healthy;
- SQLite remains structurally healthy and contains all generated readiness rows after shutdown;
- those rows survive a full Leaf restart.

Each case emits:

```text
FRONTIER_GENERATION_LOAD_CASE
  requesters=<1|10|20|40>
  requests=48
  admission_seconds=...
  total_seconds=...
  configured_rate_upper_bound=...
  peak_queue=...
  peak_in_flight=...
  mspt_p50=...
  mspt_p95=...
  mspt_p99=...
  mspt_max=...
  peak_heap_mib=...
```

The exact-head run that introduced this gate (`036b6a11566c4de1454708dca2d303dedb5b0be7`, workflow run `36265091490`) passed with an 8 chunks/s / 4 concurrent configured upper bound:

| synthetic requesters | 48-admission time | total case time | peak in-flight | MSPT p99 |
|---:|---:|---:|---:|---:|
| 1 | 8.024 s | 8.323 s | 4 | 5.213 ms |
| 10 | 7.088 s | 7.537 s | 4 | 3.721 ms |
| 20 | 7.090 s | 7.339 s | 3 | 3.391 ms |
| 40 | 7.088 s | 7.238 s | 4 | 2.976 ms |

The important result is the invariant: increasing requesters from 1 to 40 did not multiply the aggregate generation budget. The measured MSPT/heap values come from an ephemeral GitHub runner with a flat disposable world and are **not production tuning data**.

### What this suite does not prove

Synthetic requester IDs are not Minecraft clients. This suite does not validate packet timing, actual player movement, elytra/vehicle feel, network contention, client-side rubber-banding, or representative Enthusia plugin/player load.

Those require a real or headless Minecraft-client staging run on representative Enthusia hardware. Do not fake those properties inside Sentinel Sim.

## 4. Enthusia Sentinel shared staging

Repository: `wsg138/EnthusiaStaff-Staging`

Frontier publishes the standard exact-head artifact contract:

```text
artifact: sentinel-plugin
plugin JAR: plugin.jar
```

The shared Sentinel service runs Frontier's declared `startup` and `restart` profiles in its trusted rootless Paper sandbox. These profiles validate exact-artifact provenance, plugin class loading, Paper/Moonrise reflection compatibility, SQLite startup/shutdown, clean restart and state-directory reuse.

Sentinel's current generic restart executor does not execute arbitrary repository-declared `before-shutdown` / `after-restart` console actions and does not provide a real/headless Minecraft-client primitive. Frontier therefore does not claim that generic `PAPER_RESTART_OK` proves generation-load or destructive-reclaim behavior.

## 5. Real Paper Reclaim Acceptance

The repository-owned `Real Paper Reclaim Acceptance` GitHub Actions workflow is the destructive runtime gate. It runs for pull requests and again for every push to `main` so the merged commit receives its own exact-SHA evidence. It:

1. builds the exact checked-out commit with the same Java/quality gates;
2. resolves a stable Paper 1.21.11 server runtime from PaperMC's official downloads service and records its URL/SHA-256;
3. creates a disposable loopback-only server with Sentinel's exact isolated-test MOTD and at most two player slots;
4. starts Paper and waits for readiness;
5. executes `frontier acceptance prepare I_UNDERSTAND_DISPOSABLE_WORLD` from console;
6. requires `FRONTIER_ACCEPTANCE_PREPARED` before shutdown;
7. restarts the same disposable server state;
8. executes `frontier acceptance verify I_UNDERSTAND_DISPOSABLE_WORLD`;
9. requires `FRONTIER_ACCEPTANCE_RECLAIM_OK`;
10. requires both Paper cycles to stop cleanly and the acceptance state file to be removed.

The acceptance harness itself:

- generates a far-away disposable region;
- creates a protected marker chunk in a separate region;
- records actual Moonrise storage occupancy;
- durably reserves exactly those disposable chunks before destructive storage mutation;
- logically clears CHUNK_DATA, ENTITY_DATA and POI_DATA;
- invalidates generated readiness after logical clear;
- persists deletion/protection state;
- verifies those states after restart;
- physically reclaims the empty MCA region container;
- requires region-file byte usage to decrease;
- verifies the protected marker survived;
- regenerates the reclaimed chunk and proves it persists again.

The workflow always uploads bounded server logs plus Paper and plugin hashes as evidence.

## 6. Exact-main artifact

`Sentinel Plugin Artifact` runs on pull requests and again on pushes to `main`. The `main` run rebuilds the merged commit and uploads the stable contract:

```text
sentinel-plugin/plugin.jar
sentinel-plugin/plugin.jar.sha256
```

This removes ambiguity between a validated branch/PR-head JAR and the merge-commit SHA even when both commits have identical source trees. Workflows also support manual dispatch for bounded recovery/revalidation.

## 7. Safety fences

Destructive acceptance and generation-load commands operate only when all required isolated-runtime checks pass, including:

- sender is server console;
- exact confirmation token is supplied;
- server is bound to `127.0.0.1`;
- no players are online;
- `max-players <= 2`;
- MOTD is exactly `Enthusia Sentinel isolated smoke test`.

They are not general production commands.

## 8. Crash/recovery evidence

Normal CI proves the ordering invariant that destructive candidates require a SQLite-committed reclaim intent and that intents survive repository restart. Exact-key reservation is also covered so the runtime harness cannot bypass that invariant.

Generation readiness likewise remains pending until the FULL-synchronous SQLite write completes, and the real-Leaf runtime gate proves those rows reopen after process restart.

Runtime cleanup re-checks journal/MSPT/player/load/ticket/activity safety before Moonrise mutation. If final deletion persistence fails after logical clear, the persistent safety latch disables further cleanup while the durable intent remains available for diagnosis/recovery.

## 9. Exact-head rule

Runtime evidence applies only to the exact plugin source SHA and exact produced JAR. Any source, safety-relevant configuration, test-harness or workflow change invalidates prior runtime evidence. Publication therefore refuses to write a binary if the relevant branch source/validation definition advanced while the run was executing.

## 10. Production rollout order

1. exact-head CI, reproducible binary, real-Leaf startup/restart and 1/10/20/40 aggregate generation proof green;
2. Sentinel shared startup/restart compatibility green;
3. real/headless-client 1/10/20/40 frontier exploration on representative Enthusia hardware, measuring MSPT p50/p95/p99, TPS, CPU, memory/GC, queue backlog, generation latency and player edge delay;
4. calibrate global shield bands from that evidence rather than GitHub-runner numbers;
5. production generation shield enabled with cleanup disabled;
6. tracking/protection soak;
7. cleanup enabled in dry-run only;
8. inspect candidate reports/status and backup behavior;
9. production with the `100000` permanent core and destructive cleanup still dry-run;
10. explicit owner-reviewed destructive enable only after observed candidate reports and real disk-reclaim evidence are clean.

Enthusia production uses Leaf. Unsupported required generation/storage internals fail closed instead of using guessed reflection.
