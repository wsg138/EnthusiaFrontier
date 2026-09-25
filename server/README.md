# Enthusia Frontier Test server root

This directory is intentionally laid out like the root of a normal Leaf/Paper Minecraft server. Upload/copy the contents of `server/` directly into the Pterodactyl server root.

## Runtime

- Java 21
- Leaf 1.21.11 build 179
- Paper + Leaf + Gale + Purpur configs already present
- Leaf Secure Seed enabled
- Velocity modern forwarding configured
- Java + Bedrock through the existing Velocity/Geyser/Floodgate network

## World

- Overworld: **5000 x 5000**
- Nether: **2500 x 2500**
- End: **1000 x 1000** (main island only)
- End enabled immediately
- no pregeneration
- fresh fixed terrain seed
- private Leaf 1024-bit Secure Seed feature seed for ores/structures
- Frontier cleanup: real deletion after 1 untouched day

The ordinary `level-seed` controls terrain. With Leaf Secure Seed enabled, structures such as strongholds use Leaf's separate 1024-bit feature seed. Keep that live feature seed private. On the first clean boot, `/locate structure minecraft:stronghold` must return a reachable stronghold inside the ±2500 Overworld border before normal players are admitted. See `FIRST-BOOT-CHECKLIST.md`.

## Normal server layout

The folder already contains the normal runtime/config structure:

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
- `plugins/` configuration folders
- `world/datapacks/` border bootstrap
- fresh ops/whitelist/ban files
- startup and validation helpers

## Binaries and deployment-only secrets

Large server/plugin binaries and private network secrets are deliberately not embedded as text placeholders masquerading as real files. `BINARY-MANIFEST.yml` records exact expected names/hashes.

Run `populate-runtime.ps1` against a **raw/current SMP server root** to copy and hash-verify the known plugin JARs, Floodgate private key, LuckPerms network DB config, Nexo assets/config, Tags presentation config, and Velocity forwarding secret without copying SMP player/world/progression databases.

Example:

```powershell
.\populate-runtime.ps1 `
  -SourceSmpRoot 'D:\SMP' `
  -FrontierJarPath 'D:\Builds\EnthusiaFrontier-0.1.1.jar' `
  -BackendPort 25568
```

The challenge worker owns the finalized `EnthusiaTempChallenges`, `EnthusiaAdvancements`, and `UltimateAdvancementAPI` JARs/configs and will place them directly under `server/plugins/`.

`start.sh` automatically downloads and SHA-256 verifies the official Leaf 1.21.11 build 179 if the Leaf JAR is absent.

After binaries/secrets and the challenge worker artifacts are present, run:

```powershell
.\validate-runtime.ps1
```

Then complete `FIRST-BOOT-CHECKLIST.md` before opening the server.

## Velocity

The separate repository-level `velocity/` directory contains:

- `velocity.toml` — Frontier-aware reference configuration;
- `velocity.toml.fragment` — minimal backend entry;
- `apply-frontier-test.ps1` — idempotent live Velocity + VeloTAB patcher with backups/rollback;
- `plugins/velocitab/frontier-test-group.yml` — dedicated Frontier Test tab group.

Use the patch script against the live proxy rather than blindly replacing the live proxy config, because internal TEST/BUILD targets can change independently of this repository snapshot.
