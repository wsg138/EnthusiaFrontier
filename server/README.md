# Enthusia TEMP server root

This directory is laid out like the root of a normal Leaf/Paper Minecraft server. Copy the contents of `server/` directly into the empty Test2/Pterodactyl server root.

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
- End: **-500..+500** (**1,000 blocks wide; main island only**)
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
- their TEMP-specific configs/advancement tree

These are authoritative for TEMP. `populate-runtime.ps1` deliberately refuses to replace them with older SMP copies.

## Prepare Test2

For the actual Test2 deployment, use `prepare-test2.ps1`. It takes a **raw/current SMP server root** and copies only the approved regular dependencies/secrets that TEMP needs. It hash-verifies known JARs and does not copy SMP worlds, playerdata, inventories, CoreProtect data, playtime DBs or other progression state.

Example:

```powershell
.\prepare-test2.ps1 `
  -SourceSmpRoot 'D:\SMP' `
  -BackendPort 25568
```

A successful preparation ends with `TEST2_TEMP_RUNTIME_READY`. See `TEST2-DEPLOYMENT.md` for the panel memory/JVM settings and deployment sequence.

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

Use the repository-level TEMP deployment files against the live proxy rather than replacing its whole configuration:

- `velocity/velocity.toml.temp.fragment`
- `velocity/apply-temp.ps1`
- `velocity/plugins/velocitab/temp-group.yml`

The patcher creates backups, adds exactly one `TEMP` backend, keeps TEMP out of the normal fallback list, and installs the dedicated TEMP VeloTAB group. Players then join with `/server TEMP`.
