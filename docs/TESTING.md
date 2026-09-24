# Testing Strategy

Frontier has policy/persistence risk and real Paper/Moonrise storage risk, so validation is split across unit CI, Sentinel Sim and isolated real-server staging.

## 1. Normal CI

Every PR runs:

```text
gradle clean check shadowJar --no-daemon
```

Required gates:

- Java 21 compilation with `-Xlint:all -Werror`;
- JUnit domain/application/config tests;
- SQLite integration tests using temporary databases, including durable reclaim-intent restart recovery;
- MCA header/negative-coordinate/sidecar tests;
- SpotBugs at max effort / low-confidence reporting with failures enforced;
- 70% JaCoCo line coverage over the independently unit-testable core/persistence surface;
- reproducible shaded deployable JAR;
- production platform-baseline guard.

Real Bukkit/Paper/Moonrise runtime adapters are not counted in the JaCoCo denominator because JVM tests cannot execute their real contract honestly. They are still compiled with warnings-as-errors, analyzed by SpotBugs, and must pass the real-server profile below.

## 2. Enthusia Sentinel Sim

Repository: `wsg138/EnthusiaSentinel-Sim`

Frontier publishes the standard exact-head artifact contract:

```text
artifact: sentinel-plugin
plugin JAR: plugin.jar
```

Simulation is useful for plugin lifecycle and policy plumbing. It must not be cited as proof of Paper generation internals, Moonrise deletion, Anvil allocation, or physical disk reclamation.

## 3. Enthusia Staging

Repository: `wsg138/EnthusiaStaff-Staging`

The repository manifest exposes `startup` and `restart` against a disposable rootless Paper sandbox. The restart profile additionally drives Frontier's isolated destructive acceptance harness:

1. generate a far-away disposable region;
2. create and persist protected activity in a separate control region;
3. record/flush actual Moonrise storage occupancy;
4. logically clear disposable CHUNK_DATA, ENTITY_DATA and POI_DATA;
5. persist deletion/protection state;
6. restart the server against the same disposable state;
7. prove deletion/protection state survived restart;
8. physically reclaim the now-empty region container;
9. verify allocated region-file bytes decrease;
10. verify the protected marker remains intact;
11. regenerate the reclaimed chunk and prove it persists correctly.

The destructive entrypoint is fenced to console, the exact confirmation token, loopback bind, an empty server, maximum two player slots and Sentinel's isolated-test MOTD. It is not a general administrator delete command.

The staging executor validates Paper/Moonrise behavior. Because Enthusia production uses Leaf, rollout must still retain Frontier's startup compatibility probe on the exact installed Leaf build; unsupported internals fail startup when the adapter is required.

## 4. Storage acceptance metrics

Physical-reclaim evidence includes:

- logical occupied chunk state across `region`, `entities`, and `poi`;
- region-file bytes before logical/physical reclaim;
- SQLite deletion/protection state across restart;
- successful regeneration after reclaim;
- protected marker preservation.

Clearing a region header without reducing storage is not considered physical reclaim.

## 5. Crash/recovery evidence

Normal CI proves the ordering invariant that destructive candidates require a SQLite-committed reclaim intent and that an intent is recovered after repository restart. The runtime path re-checks journal/MSPT/player/load/ticket/activity safety before Moonrise mutation. If final deletion persistence fails after logical clear, the persistent safety latch disables further cleanup while the durable intent remains available for diagnosis/recovery.

## 6. Exact-head rule

Runtime evidence applies only to the exact plugin source SHA and exact produced JAR. Any code, safety-relevant configuration or acceptance change invalidates prior runtime evidence.

## 7. Production rollout order

1. zero-core disposable staging world;
2. generation throttling enabled, cleanup disabled;
3. tracking/protection soak;
4. cleanup enabled in dry-run only;
5. inspect candidate reports/status and backup behavior;
6. destructive cleanup on disposable staging data;
7. production with the `100000` permanent core and destructive cleanup still dry-run;
8. explicit owner-reviewed destructive enable only after observed candidate reports and real disk-reclaim evidence are clean.
