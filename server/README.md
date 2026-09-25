# Enthusia Frontier Test server root

This directory is laid out like the root of a normal Leaf/Paper Minecraft server. Copy the contents of `server/` directly into the Pterodactyl server root.

## Runtime

- Java 21
- Leaf 1.21.11 build 179
- Paper + Leaf + Gale + Purpur configs present
- Leaf Secure Seed enabled
- Velocity modern forwarding configured
- Java + Bedrock through the existing Velocity/Geyser/Floodgate network

## World

- Overworld: **-5000..+5000** (**10,000 blocks wide**)
- Nether: **-2500..+2500** (**5,000 blocks wide**)
- End: **-2500..+2500** (**5,000 blocks wide**)
- End enabled immediately
- Elytras disabled by the bundled datapack policy, including natural End Ship item frames, dropped Elytras and player inventories
- no pregeneration
- fresh fixed terrain seed
- private Leaf 1024-bit Secure Seed feature seed for ores/structures
- Frontier cleanup: real deletion after 1 untouched day

The ordinary `level-seed` controls terrain. With Leaf Secure Seed enabled, structures use Leaf's separate private feature seed. On the first clean boot, `/locate structure minecraft:stronghold` must return a reachable stronghold inside the ±5000 Overworld border before normal players are admitted. See `FIRST-BOOT-CHECKLIST.md`.

## Normal server layout

The folder contains the normal runtime/config structure:

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
- their Frontier-specific configs/advancement tree

These are authoritative for Frontier Test. `populate-runtime.ps1` deliberately refuses to replace them with older SMP copies.

## Populate the remaining network/runtime files

`populate-runtime.ps1` takes a **raw/current SMP server root** and copies only the regular dependencies/secrets that this server needs. It hash-verifies every known JAR and does not copy SMP worlds, playerdata, inventories, CoreProtect data, playtime DBs or other progression state.

It supplies:

- CoreProtect
- InventoryRollbackPlus
- EnthusiaPlaytime
- LuckPerms
- Floodgate-Spigot + private Floodgate key
- BedrockWindChargeFix
- Nexo + live emoji/resource-pack assets/config
- PlaceholderAPI
- TAB
- Velocity forwarding secret
- actual backend port
- verified Leaf 1.21.11-179 if missing

The Frontier JAR is published to `server/plugins/` by the branch runtime-binary workflow. You can also explicitly provide an exact JAR with `-FrontierJarPath`.

Example:

```powershell
.\populate-runtime.ps1 `
  -SourceSmpRoot 'D:\SMP' `
  -BackendPort 25568
```

The script finishes by running `validate-runtime.ps1`. A successful run prints `FRONTIER_TEST_RUNTIME_READY`.

Private deployment data is intentionally never committed:

- Velocity forwarding secret
- Floodgate `key.pem`
- LuckPerms DB credentials
- Nexo hosting secrets
- generated secure feature seed

## Starting

`start.sh` downloads and SHA-256 verifies official Leaf 1.21.11 build 179 if it is absent, then starts the server. The branch runtime-binary workflow also publishes that exact Leaf JAR into `server/` when GitHub Actions is available.

Complete `FIRST-BOOT-CHECKLIST.md` before normal players join.

## Velocity

The separate repository-level `velocity/` directory contains:

- `velocity.toml` — Frontier-aware reference configuration
- `velocity.toml.fragment` — minimal backend entry
- `apply-frontier-test.ps1` — idempotent live Velocity + VeloTAB patcher with backups/rollback
- `plugins/velocitab/frontier-test-group.yml` — dedicated Frontier Test tab group

Use the patch script against the live proxy rather than blindly replacing the live proxy config, because internal TEST/BUILD targets can change independently of this branch.
