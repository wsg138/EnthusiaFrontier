# Frontier automated staging

EnthusiaFrontier uses two real-Paper validation paths plus normal CI. They cover different trust boundaries and must not be conflated.

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

The current generic Sentinel restart executor validates manifest actions but does not execute arbitrary Frontier `before-shutdown` / `after-restart` console actions. Therefore Frontier's manifest intentionally contains no such dead actions, and `PAPER_RESTART_OK` is treated as compatibility/restart evidence rather than destructive-reclaim evidence.

## Real Paper destructive acceptance

The repository's `Real Paper Reclaim Acceptance` workflow performs the destructive proof directly on a disposable Paper 1.21.11 server. It records the resolved Paper runtime URL/SHA-256 and the exact Frontier JAR SHA-256, boots the same server state twice, and drives the guarded console acceptance command around the restart.

Required evidence markers are:

```text
FRONTIER_ACCEPTANCE_PREPARED
FRONTIER_ACCEPTANCE_RECLAIM_OK
FRONTIER_REAL_PAPER_ACCEPTANCE_OK
```

The workflow fails if Frontier emits `FRONTIER_ACCEPTANCE_FAILED`, either Paper process exits unexpectedly, the durable acceptance state is missing after prepare, or that state remains after verification.

The acceptance command additionally proves Moonrise logical chunk/entity/POI deletion, durable reclaim-intent/deletion/protection state, fully empty MCA physical unlink, byte reduction, protected marker preservation and regenerated terrain persistence.

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

Cleanup ships **disabled and dry-run by default**. Passing both real-Paper validation paths proves the capability; it does not silently authorize production deletion.

Enthusia uses Leaf, so the exact production Leaf build is still protected by Frontier's startup compatibility probes. If required generation or Moonrise internals do not match the validated adapter, Frontier fails closed instead of guessing.
