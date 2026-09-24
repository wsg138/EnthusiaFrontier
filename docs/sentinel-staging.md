# Frontier automated staging

EnthusiaFrontier uses both existing Enthusia test systems. They cover different trust boundaries and neither should be treated as a substitute for the other.

## Enthusia Sentinel Sim

`wsg138/EnthusiaSentinel-Sim` owns Frontier's central MockBukkit profile. Frontier publishes the exact PR-head artifact as:

- Actions artifact: `sentinel-plugin`
- plugin path inside the artifact: `plugin.jar`

The Sim profile loads the plugin, waits for asynchronous startup, runs `frontier status`, keeps a simulated player online, explores bounded status/no-op sequences, and rejects severe logs.

Frontier deliberately detects MockBukkit and uses `SimulationGenerationThrottleAdapter`. That adapter only records requested limits. A passing Sim check does **not** prove real Paper/Leaf MSPT sampling, Paper internal generation-limit fields, Moonrise storage behavior, vanilla chunk generation, or physical disk reclamation.

## Enthusia Staff Staging

`wsg138/EnthusiaStaff-Staging` is the existing shared real-Paper staging service. Frontier's schema-1 `.enthusia-test.yml` declares only:

- `startup`
- `restart`

The initial onboarding is manual-only. On an open, non-draft same-repository Frontier pull request, an authorized operator can request:

```text
@enthusia-sentinel test startup
@enthusia-sentinel test restart
```

Sentinel binds the request to the exact immutable Frontier commit, validates this manifest, resolves only a successful exact-SHA `sentinel-plugin` Actions artifact, and runs the plugin in its disposable rootless Paper executor under the shared one-heavy/resource gates.

These profiles prove substantially more than MockBukkit for this milestone: real Paper class loading, the reflective Paper generation-throttle adapter, SQLite startup, clean shutdown, and restart persistence/lifecycle behavior. They still do **not** authorize or validate destructive world-storage reclamation.

## Destructive cleanup boundary

Destructive region cleanup is intentionally disabled in the current Frontier milestone. Before it can be enabled by default, a later acceptance package must use a real Leaf/Paper environment with Moonrise storage and prove all of the following with disposable data:

1. only eligible frontier chunks/regions are selected;
2. protected/core/player-activity data cannot be reclaimed;
3. server quiescence and storage synchronization are correct;
4. restart/recovery after interruption is safe;
5. physical disk usage is actually reduced;
6. regenerated terrain remains valid after revisiting reclaimed space.

No MockBukkit result or ordinary startup/restart result may be presented as evidence for those destructive-storage claims.
