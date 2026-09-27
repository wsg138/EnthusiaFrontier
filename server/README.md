# Enthusia TEMP server root

This directory is the **sanitized deployment template** for the Test2 TEMP server. Do not upload the checked-in `server/` directory directly to Bloom: private forwarding/Floodgate/LuckPerms/Nexo deployment material is intentionally absent until the preparation step runs.

The preferred prepared output is `build/test2-runtime/`, created by `tools/prepare-test2-local.ps1`. Upload the contents of that prepared directory (or its generated ZIP) into the empty Test2/Pterodactyl root.

## Runtime

- Java 21
- Leaf 1.21.11 build 179
- Paper + Leaf + Gale + Purpur configs present
- Leaf Secure Seed enabled
- Velocity modern forwarding configured during deployment preparation
- Java + Bedrock through the existing Velocity/Geyser/Floodgate network

## World

- Overworld: **-5000..+5000** (**10,000 blocks wide**)
- Nether: **-2500..+2500** (**5,000 blocks wide**)
- End: **-500..+500** (**1,000 blocks wide; main island only**)
- End enabled immediately
- Elytras disabled by the bundled datapack policy, including natural End Ship item frames, dropped Elytras and player inventories
- no pregeneration
- fresh fixed terrain seed
- private Leaf 1024-bit Secure Seed feature seed for ores/structures
- Frontier cleanup: real deletion after 1 untouched day

The ordinary `level-seed` controls terrain. With Leaf Secure Seed enabled, structures use Leaf's separate private feature seed. On the first clean boot, `/locate structure minecraft:stronghold` must return a reachable stronghold inside the ±5000 Overworld border before normal players are admitted. See `FIRST-BOOT-CHECKLIST.md`.

## Normal server layout

The prepared folder contains the normal runtime/config structure:

- `server.properties`
- `eula.txt`
- `bukkit.yml`
- `spigot.yml`
- `purpur.yml`
- `commands.yml`
- `permissions.yml`
- `config/paper-global.yml`
- `config/paper-world-defaults.yml`
- `config/leaf-global.yml`
- `config/gale-global.yml`
- `config/gale-world-defaults.yml`
- `plugins/` with the finalized Frontier challenge/advancement/Tags JARs and all plugin config folders
- `world/datapacks/` with the border + no-Elytra bootstrap
- fresh ops/whitelist/ban files
- startup, population and validation helpers

## Already committed challenge runtime

The branch already contains and hash-locks:

- `plugins/EnthusiaTempChallenges-0.2.0-frontier.1.jar`
- `plugins/EnthusiaAdvancements-1.0.0-frontier.jar`
- `plugins/UltimateAdvancementAPI-2.8.1.jar`
- `plugins/EnthusiaTags.jar`
- their TEMP-specific configs/advancement tree

These are authoritative for TEMP. `populate-runtime.ps1` deliberately refuses to replace them with older SMP copies.

## Prepare Test2

Preferred path from the repository root on Lincoln's Windows machine:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\prepare-test2-local.ps1 `
  -BackendPort <TEST2_BACKEND_PORT> `
  -CreateZip
```

The helper uses the existing read-only `bloom-smp` rclone remote to copy only the approved live dependencies/configuration into an isolated, gitignored deployment copy. It does not copy SMP worlds, playerdata, inventories, CoreProtect data, playtime databases, or other progression state. It never performs a production rclone write/sync/delete operation.

A successful preparation ends with `TEST2_TEMP_RUNTIME_READY` and creates `build/test2-runtime/`; with `-CreateZip` it also creates `build/Test2-TEMP-runtime.zip`.

`prepare-test2.ps1` remains the lower-level helper for cases where a separate raw/current SMP root already exists locally. Run it only against an isolated copy of this template, not the tracked template itself, because the prepared runtime contains private deployment material.

Private deployment data is intentionally never committed:

- Velocity forwarding secret
- Floodgate `key.pem`
- LuckPerms DB credentials
- Nexo hosting secrets
- generated secure feature seed

## Starting

`start.sh` downloads and SHA-256 verifies official Leaf 1.21.11 build 179 if it is absent, then starts the server with the Test2 32 GB/ZGC JVM defaults unless the panel supplies `JAVA_ARGS`. The branch runtime-binary workflow also publishes that exact Leaf JAR into `server/` when GitHub Actions is available.

Complete `FIRST-BOOT-CHECKLIST.md` before normal players join.

## Velocity

Use the repository-level TEMP deployment files against the live proxy rather than replacing its whole configuration:

- `velocity/velocity.toml.temp.fragment`
- `velocity/apply-temp.ps1`
- `velocity/plugins/velocitab/temp-group.yml`

The patcher creates backups, adds exactly one `TEMP` backend, keeps TEMP out of the normal fallback list, and installs the dedicated TEMP VeloTAB group. Players then join with `/server TEMP`.
