# Enthusia TEMP — first boot acceptance

Do not open the temporary server to normal players until the required checks below pass.

## Runtime

- Java 25.
- Paper `26.3` only; verify the exact runtime SHA-256 against `BINARY-MANIFEST.yml`.
- Confirm no legacy `leaf-1.21.11-179.jar` or Leaf-only startup flags/config requirements remain.
- Confirm no `REPLACE_WITH_*` or `<REDACTED>` placeholders remain in runtime configs.
- Confirm `plugins/floodgate/key.pem` exists locally and is not committed.
- Keep the generated world seed private until the TEMP event is complete.

## Fresh world / borders

From console after the first clean boot:

```text
worldborder get
execute in minecraft:the_nether run worldborder get
execute in minecraft:the_end run worldborder get
locate structure minecraft:stronghold
```

Expected border widths (`/worldborder get` reports full width):

- Overworld: `10000` → coordinates `-5000..+5000`
- Nether: `5000` → coordinates `-2500..+2500`
- End: `1000` → coordinates `-500..+500` (main island only)

The checked-in TEMP template leaves `level-seed=` blank so Paper creates a fresh random world. Before normal players join, the located stronghold must be inside X/Z `-5000..+5000` with enough margin to reach the structure normally. If it is outside the border, stop the server, delete the three fresh world directories (`world`, `world_nether`, `world_the_end`), and regenerate with another fresh random seed. Do **not** widen the ±5000 Overworld border.

Do not publish `/seed` output before the TEMP event is complete.

## End / Elytra rule

- End must be accessible immediately; there is no scheduled End opening.
- Verify the natural stronghold portal can be reached inside the Overworld border.
- Verify the End border is `1000` blocks wide / `-500..+500`.
- Confirm the bundled `enthusia_test:tick` function is active.
- Confirm an End gateway cannot be used to remain outside the main-island border.
- Locate or spawn an Elytra item frame in a controlled admin test and confirm the Elytra is removed.
- Drop/give an Elytra in a controlled admin test and confirm it is removed from item entities/player inventories.
- Elytra server-first must remain locked by the challenge plugin.

## Proxy / Java / Bedrock

- `TEMP` exists exactly once in live Velocity and points at `170.205.24.14:25566`.
- `TEMP` is **not** in the normal Velocity `try` fallback list.
- Dedicated TEMP VeloTAB group loads with no raw/missing placeholders.
- `/server TEMP` routes to Test2.
- Join through Velocity from Java.
- Join through the normal Geyser endpoint from Bedrock.
- Confirm Floodgate identity is preserved rather than creating a second offline Java identity.

## Plugin load

Required baseline plugins:

- EnthusiaFrontier
- CoreProtect
- InventoryRollbackPlus
- EnthusiaPlaytime
- LuckPerms
- Floodgate-Spigot
- BedrockWindChargeFix
- EnthusiaTags
- Nexo
- PlaceholderAPI
- TAB
- EnthusiaTempChallenges

`EnthusiaAdvancements` and `UltimateAdvancementAPI` are intentionally **not** part of the Paper 26.3 TEMP runtime. Their NMS-backed presentation path is not yet 26.3-compatible. The authoritative portable first-reward entitlement is written as a context-free/global LuckPerms node; custom advancement presentation can be reconciled later without changing the recorded winner.

There should be no Chunky/pregeneration, homes/teleports, LumaGuilds, economy/market stack, Plan or copied SMP gameplay suite unless deliberately added later.

## Challenge rewards

- Run `/tempchallenge status` and confirm `frontier_2026_test` is `ACTIVE`.
- Confirm first Elytra remains locked.
- Test a reversible/admin first-award flow before players are admitted.
- Confirm the winner survives restart and duplicate award attempts do not create a second winner.
- Confirm the portable reward is a global/context-free LuckPerms entitlement, not a `server=EnthusiaTEMP`-scoped node.
- Tags may provide presentation on TEMP; item/inventory progression must never transfer to the main SMP.

## Storage isolation

- Frontier: local SQLite.
- CoreProtect: local SQLite.
- Playtime: local SQLite; rewards/export off.
- InventoryRollbackPlus: local files.
- LuckPerms: shared network MariaDB with unique `EnthusiaTEMP` server identity; portable first entitlements themselves remain context-free/global.
- No production world/player/inventory/plugin-runtime databases copied into this server.

## Frontier destructive controls

Run `/frontier status` and confirm:

- cleanup enabled;
- dry-run disabled;
- 1-day untouched retention;
- audit logging enabled;
- physical reclaim enabled;
- Overworld core radius 0;
- Nether and End unmanaged by Frontier cleanup.

Record day-one coordinates for:

1. fly-through-only terrain — should be reclaimed after retention;
2. placed/broken block — should survive;
3. interacted container/block — should survive;
4. untouched chunk with a nearby player — must defer while player is nearby;
5. reclaimed chunk revisited later — must regenerate cleanly.

Preserve all `FRONTIER_CLEANUP_AUDIT` lines.

## Operational safety

Stop destructive testing and preserve logs/data if:

- Frontier's safety latch trips;
- a protected chunk is deleted;
- a player-adjacent chunk is cleared;
- regenerated terrain is corrupt/inconsistent;
- plugin persistence reports an unrecoverable error.
