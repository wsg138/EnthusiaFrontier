# Frontier automated staging

EnthusiaFrontier uses both existing Enthusia test systems. They cover different trust boundaries and neither substitutes for the other.

## Enthusia Sentinel Sim

`wsg138/EnthusiaSentinel-Sim` owns Frontier's MockBukkit profile. Frontier publishes the exact PR-head artifact as:

- Actions artifact: `sentinel-plugin`
- plugin path inside the artifact: `plugin.jar`

Simulation validates plugin lifecycle/policy plumbing only. It does **not** validate Paper/Leaf generation internals, Moonrise storage, vanilla chunk generation or physical disk reclamation.

## Enthusia Staff Staging

`wsg138/EnthusiaStaff-Staging` is the shared real-Paper staging service. Frontier's schema-1 `.enthusia-test.yml` exposes:

- `startup`
- `restart`

On an open, non-draft same-repository Frontier pull request, an authorized operator can request:

```text
@enthusia-sentinel test startup
@enthusia-sentinel test restart
```

Sentinel binds the request to the exact immutable Frontier commit, validates the manifest, resolves only a successful exact-SHA `sentinel-plugin` Actions artifact, and runs the plugin in its disposable rootless Paper executor under shared queue/resource gates.

`startup` proves real Paper class loading, reflective generation/storage adapter compatibility, SQLite startup and clean shutdown.

`restart` additionally runs the isolated destructive acceptance actions declared by Frontier's manifest. Before the first shutdown it executes the guarded `frontier acceptance prepare` phase and waits for `FRONTIER_ACCEPTANCE_PREPARED`. After restart it executes `frontier acceptance verify` and waits for `FRONTIER_ACCEPTANCE_RECLAIM_OK`.

That acceptance covers actual Moonrise logical chunk/entity/POI deletion, durable ledger state across restart, fully empty MCA physical unlink, byte reduction, protected marker preservation and regenerated terrain persistence.

## Destructive safety fence

The acceptance command only operates when all of these are true:

- sender is the server console;
- exact confirmation token is supplied;
- server is loopback-bound to `127.0.0.1`;
- no players are online;
- `max-players <= 2`;
- MOTD is exactly Sentinel's isolated smoke-test MOTD.

A normal Enthusia server therefore refuses the acceptance command even if an operator knows the token.

## Production boundary

Cleanup code ships **disabled and dry-run by default**. Passing disposable staging proves the capability; it does not silently authorize production deletion. Production enablement remains a deliberate rollout decision after dry-run observation and backup/candidate review.

Staging uses Paper/Moonrise. Enthusia uses Leaf, so the exact production Leaf build is still protected by startup compatibility probes: when the storage/generation adapters are required, unsupported internals fail startup rather than falling back to guessed reflection.
