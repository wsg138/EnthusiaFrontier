# Testing Strategy

Frontier has policy/persistence risk and real Paper/Moonrise storage risk, so validation is split across unit CI, shared Sentinel smoke tests, and a dedicated destructive real-Paper acceptance run.

## 1. Normal CI

Every PR runs:

```text
gradle clean check shadowJar --no-daemon
```

Required gates:

- Java 21 compilation with `-Xlint:all -Werror`;
- JUnit domain/application/config tests;
- SQLite integration tests using temporary databases, including durable reclaim-intent restart recovery and exact-key reservation;
- MCA header/negative-coordinate/sidecar tests;
- SpotBugs at max effort / low-confidence reporting with failures enforced;
- 70% JaCoCo line coverage over the independently unit-testable core/persistence surface;
- reproducible shaded deployable JAR;
- production platform-baseline guard.

Real Bukkit/Paper/Moonrise runtime adapters are not counted in the JaCoCo denominator because JVM tests cannot execute their real contract honestly. They are still compiled with warnings-as-errors and analyzed by SpotBugs.

## 2. Enthusia Sentinel

Repository: `wsg138/EnthusiaStaff-Staging`

Frontier publishes the standard exact-head artifact contract:

```text
artifact: sentinel-plugin
plugin JAR: plugin.jar
```

The shared Sentinel service runs Frontier's declared `startup` and `restart` profiles in its trusted rootless Paper sandbox. These profiles validate exact-artifact provenance, plugin class loading, Paper/Moonrise reflection compatibility, SQLite startup/shutdown, clean restart, and state-directory reuse.

Sentinel's current generic restart executor does not execute arbitrary repository-declared `before-shutdown` / `after-restart` console actions. Frontier therefore does not claim that `PAPER_RESTART_OK` proves destructive reclaim behavior.

## 3. Real Paper Reclaim Acceptance

The repository-owned `Real Paper Reclaim Acceptance` GitHub Actions workflow is the destructive runtime gate. It:

1. builds the exact PR head with the same Java/quality gates;
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
- persists deletion/protection state;
- verifies those states after restart;
- physically reclaims the empty MCA region container;
- requires region-file byte usage to decrease;
- verifies the protected marker survived;
- regenerates the reclaimed chunk and proves it persists again.

The workflow always uploads bounded server logs plus Paper and plugin hashes as evidence.

## 4. Destructive safety fence

The acceptance command operates only when all of these are true:

- sender is server console;
- exact confirmation token is supplied;
- server is bound to `127.0.0.1`;
- no players are online;
- `max-players <= 2`;
- MOTD is exactly `Enthusia Sentinel isolated smoke test`.

It is not a general production delete command.

## 5. Crash/recovery evidence

Normal CI proves the ordering invariant that destructive candidates require a SQLite-committed reclaim intent and that intents survive repository restart. Exact-key reservation is also covered so the runtime harness cannot bypass that invariant.

Runtime cleanup re-checks journal/MSPT/player/load/ticket/activity safety before Moonrise mutation. If final deletion persistence fails after logical clear, the persistent safety latch disables further cleanup while the durable intent remains available for diagnosis/recovery.

## 6. Exact-head rule

Runtime evidence applies only to the exact plugin source SHA and exact produced JAR. Any code, safety-relevant configuration, test-harness, or workflow change invalidates prior runtime evidence.

## 7. Production rollout order

1. exact-head CI, artifact, Sentinel startup/restart, and real-Paper reclaim acceptance all green;
2. production generation throttling enabled with cleanup disabled;
3. tracking/protection soak;
4. cleanup enabled in dry-run only;
5. inspect candidate reports/status and backup behavior;
6. production with the `100000` permanent core and destructive cleanup still dry-run;
7. explicit owner-reviewed destructive enable only after observed candidate reports and real disk-reclaim evidence are clean.

Enthusia production uses Leaf rather than stock Paper. The exact installed Leaf build is still protected by startup compatibility probes: when the generation/storage adapters are required, unsupported internals fail closed instead of using guessed reflection.
