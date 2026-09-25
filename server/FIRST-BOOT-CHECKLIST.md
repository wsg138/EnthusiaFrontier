# Enthusia Frontier Test — first boot acceptance

Do not open the temporary server to normal players until the required checks below pass.

## Runtime

- Java 21.
- Leaf `1.21.11-179` only; verify SHA-256 against `BINARY-MANIFEST.yml`.
- `config/leaf-global.yml` must have `misc.secure-seed.enabled: true`.
- Confirm no `REPLACE_WITH_*` or `<REDACTED>` placeholders remain in runtime configs.
- Confirm `plugins/floodgate/key.pem` exists locally and is not committed.

## Fresh world / borders

From console after the first clean boot:

```text
worldborder get
execute in minecraft:the_nether run worldborder get
execute in minecraft:the_end run worldborder get
locate structure minecraft:stronghold
```

Expected border widths:

- Overworld: `5000`
- Nether: `2500`
- End: `1000`

Leaf Secure Seed uses a separate private 1024-bit feature seed for structures and ores. The fixed `level-seed` therefore does **not** preselect the stronghold position. Before normal players join, the located stronghold must be inside X/Z `-2500..+2500` with enough margin to reach the structure normally. If it is outside the border, delete all three fresh world directories and regenerate once with a new secure feature seed; do not widen the 5k border.

Keep the generated secure feature seed private. Do not commit it or publish `/seed` output.

## End rule

- End must be accessible immediately; there is no scheduled End opening.
- Verify the natural stronghold portal can be reached inside the Overworld border.
- Verify the End border is 1000 blocks wide and outer End islands / End Cities cannot be reached normally.
- Elytra server-first must remain locked by the challenge plugin.

## Proxy / Java / Bedrock

- `FRONTIER_TEST` exists exactly once in live Velocity and points at the real EnthusiaState allocation.
- `FRONTIER_TEST` is **not** in the normal Velocity `try` fallback list.
- Dedicated VeloTAB group loads with no raw/missing placeholders.
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
- the finalized EnthusiaTempChallenges plugin
- EnthusiaAdvancements
- UltimateAdvancementAPI

There should be no Chunky/pregeneration, homes/teleports, LumaGuilds, economy/market stack, Plan or copied SMP gameplay suite unless deliberately added later.

## Storage isolation

- Frontier: local SQLite.
- CoreProtect: local SQLite.
- Playtime: local SQLite; rewards/export off.
- InventoryRollbackPlus: local files.
- LuckPerms: shared network MariaDB with `server: EnthusiaFrontierTest`.
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
