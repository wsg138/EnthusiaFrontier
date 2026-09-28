# Enthusia TEMP server root

This directory is the **sanitized deployment template** for the Test2 TEMP server. Do not upload the checked-in `server/` directory directly to Bloom: private forwarding/Floodgate/LuckPerms/Nexo deployment material is intentionally absent until the preparation step runs.

The preferred prepared output is `build/test2-runtime/`, created by `tools/prepare-test2-local.ps1`. Upload the contents of that prepared directory (or its generated ZIP) into the empty Test2/Pterodactyl root.

## Runtime

- Java 25
- workflow-pinned Paper 26.3 runtime (`paper-26.3.jar`)
- Paper configs plus only the plugin/config files needed by TEMP
- Velocity modern forwarding configured during deployment preparation
- Java + Bedrock through the existing Velocity/Geyser/Floodgate network

The deployable runtime and plugin JAR hashes are authoritative in `BINARY-MANIFEST.yml`. Preparation fails closed if they do not match.

## World

- Overworld: **-5000..+5000** (**10,000 blocks wide**)
- Nether: **-2500..+2500** (**5,000 blocks wide**)
- End: **-500..+500** (**1,000 blocks wide; main island only**)
- End enabled immediately
- Elytras disabled by the bundled datapack policy, including natural End Ship item frames, dropped Elytras and player inventories
- no pregeneration
- fresh random Paper world seed on first creation
- Frontier cleanup: real deletion after 1 untouched day

On the first clean boot, `/locate structure minecraft:stronghold` must return a reachable stronghold inside the ±5000 Overworld border before normal players are admitted. If not, regenerate the three fresh worlds with another random seed rather than widening the border. See `FIRST-BOOT-CHECKLIST.md`.

## Normal server layout

The prepared folder contains the normal runtime/config structure, including:

- `paper-26.3.jar`
- `server.properties`
- `eula.txt`
- `bukkit.yml`
- `spigot.yml`
- `commands.yml`
- `permissions.yml`
- `config/paper-global.yml`
- `config/paper-world-defaults.yml`
- `plugins/` with the finalized minimal TEMP runtime and configs
- `world/datapacks/` with the border + no-Elytra bootstrap
- fresh ops/whitelist/ban files
- startup, population and validation helpers

Legacy Leaf 1.21.11 runtime/config requirements are not part of the Paper 26.3 deployment.

## Challenge runtime

The Paper 26.3 TEMP challenge runtime contains and hash-locks:

- `plugins/EnthusiaTempChallenges-0.2.0-frontier.1.jar`
- `plugins/EnthusiaTags.jar`
- their TEMP-specific configs

`EnthusiaAdvancements` and `UltimateAdvancementAPI` are intentionally omitted on 26.3 because the old NMS-backed presentation layer is not 26.3-compatible. Winner persistence does not depend on it: portable challenge ownership is recorded through a context-free/global LuckPerms entitlement and the local challenge ledger.

`populate-runtime.ps1` deliberately refuses to replace the workflow-built challenge JARs with older SMP copies.

## Prepare Test2

Preferred path on Lincoln's Windows machine:

```powershell
$u='https://raw.githubusercontent.com/wsg138/EnthusiaFrontier/refs/heads/server/frontier-test-runtime/tools/bootstrap-test2.ps1'
$p="$env:TEMP\bootstrap-test2.ps1"
Invoke-WebRequest -UseBasicParsing $u -OutFile $p
& $p
```

The bootstrap refreshes the exact branch and defaults to the Test2 backend port `25566`. The lower-level equivalent with the existing checkout is:

```powershell
$r="$env:LOCALAPPDATA\Enthusia-TEMP-Deploy\EnthusiaFrontier"
git -C $r fetch origin server/frontier-test-runtime
git -C $r reset --hard origin/server/frontier-test-runtime
& "$r\tools\prepare-test2-local.ps1" -BackendPort 25566 -CreateZip
```

The helper uses the existing read-only `bloom-smp` rclone remote to copy only the approved live dependencies/configuration into an isolated, gitignored deployment copy. It does not copy SMP worlds, playerdata, inventories, CoreProtect data, playtime databases, or other progression state. It never performs a production rclone write/sync/delete operation.

A successful preparation prints both `TEMP_RUNTIME_READY` and `TEST2_TEMP_RUNTIME_READY` and creates `build/test2-runtime/`; with `-CreateZip` it also creates `build/Test2-TEMP-runtime.zip`.

Private deployment data is intentionally never committed:

- Velocity forwarding secret
- Floodgate `key.pem`
- LuckPerms DB credentials
- Nexo hosting secrets
- generated live world seed

## Starting

Use Java 25 and launch `paper-26.3.jar`. The default Test2 JVM arguments use a fixed 32 GB heap with ZGC. Do not use the old `-DLeaf.*` startup flags.

Complete `FIRST-BOOT-CHECKLIST.md` before normal players join.

## Velocity

Use the repository-level TEMP deployment files against the live proxy rather than replacing its whole configuration:

- `velocity/velocity.toml.temp.fragment`
- `velocity/apply-temp.ps1`
- `velocity/plugins/velocitab/temp-group.yml`

The patcher creates backups, adds exactly one `TEMP = "170.205.24.14:25566"` backend, keeps TEMP out of the normal fallback list, and installs the dedicated TEMP VeloTAB group. Players then join with `/server TEMP`.
