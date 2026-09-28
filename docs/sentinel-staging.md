# Frontier automated staging

EnthusiaFrontier uses several validation paths because startup compatibility, aggregate generation admission, real-client movement, and destructive Moonrise reclaim are different trust boundaries. Evidence from one path must not be presented as proof of another.

## Enthusia Sentinel shared staging

`wsg138/EnthusiaStaff-Staging` owns the trusted rootless Paper service. Frontier publishes the exact PR-head artifact as:

- Actions artifact: `sentinel-plugin`
- plugin path inside the artifact: `plugin.jar`

Frontier declares `startup` and `restart` in `.enthusia-test.yml`.

On an open, non-draft same-repository Frontier pull request, an authorized operator can request:

```text
@enthusia-sentinel test startup
@enthusia-sentinel test restart
```

Sentinel binds the request to the exact immutable Frontier commit and successful exact-SHA artifact. `startup` proves the plugin can load and shut down cleanly in the trusted Paper sandbox. `restart` proves two clean Paper cycles against one disposable state directory.

The current generic Sentinel executor does **not** provide a headless Minecraft-client primitive and does not execute arbitrary Frontier `before-shutdown` / `after-restart` console actions. Therefore:

- `PAPER_RESTART_OK` is compatibility/restart evidence;
- it is not a multi-player exploration/load proof;
- it is not destructive-reclaim proof;
- packet/client movement behavior must not be faked inside Sentinel Sim.

## Repository-owned real Leaf generation gate

The `Publish Frontier server runtime binaries` workflow validates the exact deployable JAR against pinned Leaf 1.21.11-179 before the binary can be published.

It verifies:

- byte-for-byte reproducible Frontier JAR builds;
- pinned Leaf SHA-256;
- real Leaf enable/status/shutdown;
- the global Generation Shield uses the production Paper async chunk-generation adapter;
- 48 virgin managed chunks are generated in each of four cases distributed across 1, 10, 20 and 40 synthetic requester IDs;
- increasing requester count cannot multiply the configured whole-server generation-rate budget;
- global in-flight generation stays within the configured aggregate concurrency ceiling;
- SQLite readiness rows are durable;
- the same Leaf/world/plugin state restarts cleanly and reopens those rows;
- no persistent safety latch appears.

The harness emits queue/in-flight, admission duration, MSPT p50/p95/p99/max and heap measurements to the Leaf log artifact.

The exact-head validation run `36265091490` for source `036b6a11566c4de1454708dca2d303dedb5b0be7` passed all four cases. This is real Leaf generation with synthetic requesters, not simulated generation.

## Real-client staging boundary

Synthetic requesters intentionally do **not** pretend to be Minecraft players. They prove the aggregate Generation Shield and real Leaf generation/readiness path, but they do not prove:

- packet timing;
- real player view/chunk request behavior;
- elytra/high-speed traversal feel;
- vehicle rollback feel;
- teleport/portal UX under load;
- network contention;
- client rubber-banding/delay;
- representative Enthusia CPU/plugin/player load.

Before production calibration, run real or headless Minecraft clients with at least 1, 10, 20 and 40 simultaneous frontier explorers on representative Enthusia hardware. Measure MSPT p50/p95/p99, TPS, CPU, memory/GC, generation latency, queue depth/backlog and movement/edge delay. The GitHub runner's performance numbers must not be used as production capacity estimates.

`wsg138/EnthusiaSentinel-Sim` can support deterministic simulation for boundaries it actually models, but it should not invent client/network physics that it does not possess.

## Real Paper destructive acceptance

The repository's `Real Paper Reclaim Acceptance` workflow performs the destructive proof directly on a disposable Paper 1.21.11 server. It records the resolved Paper runtime URL/SHA-256 and exact Frontier JAR SHA-256, boots the same server state twice, and drives the guarded console acceptance command around the restart.

Required evidence markers are:

```text
FRONTIER_ACCEPTANCE_PREPARED
FRONTIER_ACCEPTANCE_RECLAIM_OK
FRONTIER_REAL_PAPER_ACCEPTANCE_OK
```

The workflow fails if Frontier emits `FRONTIER_ACCEPTANCE_FAILED`, either Paper process exits unexpectedly, the durable acceptance state is missing after prepare, or that state remains after verification.

The acceptance command additionally proves Moonrise logical chunk/entity/POI deletion, durable reclaim-intent/deletion/protection state, generated-readiness invalidation, fully empty MCA physical unlink, byte reduction, protected marker preservation and regenerated terrain persistence.

## Safety fences

The destructive acceptance and generation-load commands only operate inside the isolated test runtime. Their checks include:

- sender is server console;
- exact confirmation token is supplied;
- server is loopback-bound to `127.0.0.1`;
- no players are online;
- `max-players <= 2`;
- MOTD is exactly Sentinel's isolated smoke-test MOTD.

A normal Enthusia server therefore refuses these test commands even if an operator knows the token.

## Production boundary

Cleanup ships **disabled and dry-run by default**. Passing real-Paper/Leaf validation proves capability; it does not silently authorize production deletion.

The current global Generation Shield values are staging starting points only. Production values must come from representative Enthusia hardware with real/headless clients. Unsupported required generation or Moonrise internals fail closed instead of guessing.
