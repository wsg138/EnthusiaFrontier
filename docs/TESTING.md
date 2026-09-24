# Testing Strategy

Frontier has two kinds of risk and therefore two different test backends.

## 1. Normal CI

Every PR runs:

```text
gradle clean check shadowJar --no-daemon
```

Required gates:

- Java 21 compilation with `-Xlint:all -Werror`;
- JUnit tests;
- SQLite adapter integration tests using temporary databases;
- SpotBugs at max effort / low-confidence reporting with failures enforced;
- JaCoCo line coverage gate;
- reproducible shaded deployable JAR;
- production platform-baseline guard.

## 2. Enthusia Sentinel Sim

Repository: `wsg138/EnthusiaSentinel-Sim`

Frontier publishes the standard exact-head artifact contract:

```text
artifact: sentinel-plugin
plugin JAR: plugin.jar
```

Sentinel's centrally owned profile should test behavior that simulation can honestly model:

- plugin boot/config validation;
- `/frontier status` command permission/shape;
- generation/activity event ingestion where supported;
- core-radius `0` semantics;
- block place/break/interact protection paths;
- queue safety state;
- clean disable/re-enable and data persistence where the profile backend supports it;
- fuzzing event order around generation/activity/restart boundaries.

Sentinel Sim must **not** claim to validate Paper internal generation controls, Moonrise deletes, Anvil allocation or physical disk reclamation. Those belong to real Paper/Leaf.

The preferred integration is the existing Enthusia Sentinel GitHub App and a centrally approved `profiles/enthusia-frontier/...` policy entry.

## 3. Enthusia Staging

Repository: `wsg138/EnthusiaStaff-Staging`

Staging owns real-server acceptance because Frontier depends on real Paper/Leaf world generation and eventually Moonrise region storage.

The Frontier staging profile should use a disposable world and `core-radius-blocks: 0` so every generated chunk is under management.

Minimum real-server scenarios:

1. clean boot and restart with a fresh ledger;
2. exact-head artifact provenance validation;
3. generate virgin terrain with controlled explorers;
4. verify database generation records match observed chunks;
5. create a base/activity area and verify its protection survives restart;
6. drive synthetic load/MSPT and verify throttle band transitions and recovery;
7. prove original Paper generation settings restore on plugin disable;
8. dry-run cleanup candidate report;
9. later, logical deletion/regeneration acceptance;
10. later, physical disk reclamation measurement;
11. later, kill/crash at every destructive journal phase and verify recovery;
12. verify no production world/database/credentials are reachable from the disposable profile.

The staging workflow must not modify the existing production or shared test worlds. Frontier destructive tests require a dedicated disposable world directory and explicit path guards.

## 4. Storage acceptance metrics

A physical-reclaim test records before/after:

- logical occupied chunk count in `region`, `entities`, and `poi`;
- file sizes;
- allocated filesystem blocks where available (`du`/stat evidence);
- SQLite ledger candidate/protection counts;
- regenerated chunk hashes/behavior where appropriate;
- protected-area checksums/snapshots.

A test only passes the storage goal when allocated disk usage is actually reclaimed. Clearing the region header alone is not sufficient.

## 5. Exact-head rule

Runtime evidence applies only to the exact plugin source SHA and exact produced JAR hash. Any code or safety-relevant test change invalidates prior runtime evidence.

## 6. Production rollout order

1. zero-core disposable staging world;
2. generation throttling enabled, cleanup disabled;
3. tracking/protection soak test;
4. cleanup dry-run only;
5. destructive cleanup on disposable staging data;
6. production with `100000` permanent core and destructive cleanup still dry-run;
7. owner-reviewed destructive enable only after observed candidate reports and real disk-reclaim evidence are clean.
