# Four-day destructive cleanup test

This profile is for the temporary Enthusia test server. It intentionally performs real logical chunk deletion and physical region reclamation after a short retention window.

## Server configuration

Use `docs/four-day-test-config.yml` as `plugins/EnthusiaFrontier/config.yml`.

The temporary server uses an Overworld border of **-5000..+5000** (**10,000 blocks wide**). Because the entire test world is disposable, this profile sets `core-radius-blocks: 0`, which makes every newly generated overworld chunk inside that border eligible for Frontier management. This is intentionally different from production, where the existing roughly 100,000-block pregenerated core remains permanent.

The Nether and End runtime borders are both **-2500..+2500** (**5,000 blocks wide**). Frontier destructive cleanup remains intentionally limited to the Overworld for this test.

The important cleanup settings are:

- cleanup enabled
- destructive mode (`dry-run: false`)
- 1-day untouched retention
- scan every 1200 ticks (about one minute at 20 TPS)
- one chunk clear per tick maximum
- eight-chunk player safety distance
- 35 MSPT cleanup ceiling
- physical reclaim enabled
- cleanup audit logging enabled

The normal bundled `config.yml` remains safe: cleanup disabled, dry-run enabled, 30-day retention, and audit logging disabled.

## What should happen

Movement through a temporary frontier chunk does not protect it. Meaningful activity does. Building, breaking blocks, bucket use, and interaction with a block are protection signals and protect the configured two-chunk radius.

A chunk that reaches 24 hours old without protection becomes eligible on a later cleanup scan. A candidate can still be deferred if the mutation journal is busy, MSPT is too high, a player/loaded-chunk safety check fails, or the safety latch is tripped.

Audit entries begin with `FRONTIER_CLEANUP_AUDIT` and include the world, chunk or region coordinates, outcome, and reason. Chunk decisions also include generation and reclaim-intent timestamps.

## Day-one control cases

Create several known areas inside the Overworld ±5000 test border and record their coordinates:

1. **Fly-through:** generate chunks and only travel through them. They should remain temporary and become eligible for cleanup after 24 hours.
2. **Build:** place or break a block. The touched chunk and configured protection radius must survive cleanup.
3. **Interaction:** interact with a block/container. The area must survive cleanup.
4. **Nearby player:** leave an otherwise untouched candidate near a player when it becomes eligible. Cleanup should defer while the environment is unsafe, then clear it after the player leaves if it remains unprotected.
5. **Regeneration:** revisit a successfully deleted area and regenerate it. It must load as newly generated terrain without corrupting the ledger or preventing later tracking.

## Evidence to retain

At least once per day, save:

- `/frontier status`
- all `FRONTIER_CLEANUP_AUDIT` log lines
- server MSPT/tick-health evidence around cleanup activity
- coordinates and expected result for the control cases
- disk usage for the world's `region`, `entities`, and `poi` directories

For the final review, compare expected control-case behavior against the audit trail and confirm that protected chunks survived, fly-through chunks were cleared, regenerated chunks behaved normally, and physical reclaim occurred without a safety-latch trip.

## Stop conditions

Stop destructive testing and preserve the server/logs if the cleanup safety latch trips, a protected control chunk is removed, a chunk is cleared while a player is inside the safety radius, regenerated terrain is inconsistent, or unexplained storage corruption appears.
