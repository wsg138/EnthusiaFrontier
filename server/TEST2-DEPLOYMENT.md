# Test2 TEMP deployment

Target: the existing Bloom `Test2` allocation, wiped completely before deployment.
Backend allocation: `170.205.24.14:25566`.
Public Velocity name: `TEMP` so players can use `/server TEMP`.

## Panel resources

- Java: 25
- CPU: keep the existing 3200% allocation for initial testing
- Panel memory limit: 48 GB
- JVM heap: fixed 32 GB (`-Xms32G -Xmx32G`)
- Do not allocate more CPU/RAM unless live profiling shows an actual bottleneck

## JVM flags

Use:

```text
-Xms32G -Xmx32G -XX:+UseZGC -XX:+DisableExplicitGC -XX:+AlwaysPreTouch -XX:+ExitOnOutOfMemoryError -Xlog:gc*:logs/gc-zgc.log:time,uptime,level,tags:filecount=5,filesize=10M -Dterminal.jline=false -Dterminal.ansi=true -Duser.timezone=America/Indiana/Indianapolis
```

Do **not** use the old Leaf-only flags (`-DLeaf.enableFMA`, `-DLeaf.disable-vanilla-profiler`, `-DLeaf.disable-vanilla-debug-feature`) on the Paper 26.3 TEMP runtime.

The committed `logs/` directory exists so the GC log path is valid on the first start.

## Preferred local preparation

On Lincoln's Windows machine, use the existing read-only `bloom-smp` rclone remote. The bootstrap helper refreshes the exact runtime branch first and defaults to Test2 port `25566`.

```powershell
$u='https://raw.githubusercontent.com/wsg138/EnthusiaFrontier/refs/heads/server/frontier-test-runtime/tools/bootstrap-test2.ps1'
$p="$env:TEMP\bootstrap-test2.ps1"
Invoke-WebRequest -UseBasicParsing $u -OutFile $p
& $p
```

Or, with the existing local checkout:

```powershell
$r="$env:LOCALAPPDATA\Enthusia-TEMP-Deploy\EnthusiaFrontier"
git -C $r fetch origin server/frontier-test-runtime
git -C $r reset --hard origin/server/frontier-test-runtime
& "$r\tools\prepare-test2-local.ps1" -BackendPort 25566 -CreateZip
```

The helper:

- creates an isolated deployment copy at `build/test2-runtime` instead of injecting secrets into tracked repository files;
- reads only the exact approved dependencies/configuration from the production SMP through `bloom-smp`;
- never runs rclone `sync`, `move`, `delete` or another production write operation;
- verifies Paper 26.3 and all deployable JARs against the authoritative `BINARY-MANIFEST.yml`;
- uses `server/prepare-test2.ps1` and the static validator against that isolated deployment copy;
- removes its temporary raw-SMP staging directory afterward;
- optionally creates `build/Test2-TEMP-runtime.zip` for upload.

The `build/` directory is gitignored. The produced runtime/archive contains private deployment material and must not be committed or shared publicly.

## Lower-level preparation

If a separate raw/current SMP root already exists locally, the lower-level helper can still be run inside an isolated copy of `server/`:

```powershell
.\prepare-test2.ps1 -SourceSmpRoot 'D:\path\to\raw-current-smp' -BackendPort 25566
```

Do not use the sanitized GitHub server snapshot as `SourceSmpRoot`; private forwarding/Floodgate/LuckPerms/Nexo values are intentionally absent there.

## Upload

After both `TEMP_RUNTIME_READY` and `TEST2_TEMP_RUNTIME_READY`, upload the **contents** of `build/test2-runtime/` into the completely empty Test2 root. Do not nest the `test2-runtime` directory itself. If the ZIP was created, upload/extract its contents at the Test2 root.

The first server start must use the workflow-published `paper-26.3.jar` and Java 25.

## Proxy

Patch the live Velocity and VeloTAB files with `velocity/apply-temp.ps1` using `170.205.24.14:25566`. The patcher creates backups, removes stale `FRONTIER_TEST` aliases, creates exactly one `TEMP` backend, and refuses to put TEMP in the normal fallback list.

## First boot

Complete every check in `FIRST-BOOT-CHECKLIST.md`.

Required before players are admitted:

- Overworld border reports `10000` (±5000)
- Nether border reports `5000` (±2500)
- End border reports `1000` (±500; main island only)
- reachable natural stronghold is inside the Overworld border
- End is immediately accessible
- End gateways cannot provide usable access outside the main-island border
- Elytra removal works for frames, item entities, and player inventories
- Java joins through Velocity
- Bedrock joins through the normal Geyser endpoint with Floodgate identity preserved
- `/server TEMP` works
- Frontier reports real cleanup enabled, dry-run disabled, 1-day retention, physical reclaim enabled
- no production progression databases were copied
- EnthusiaTempChallenges and EnthusiaTags load without errors
- the challenge event is ACTIVE and portable first entitlements are global/context-free LuckPerms nodes

The old `EnthusiaAdvancements` + `UltimateAdvancementAPI` presentation stack is intentionally omitted from TEMP until a 26.3-compatible implementation is available. It is not authoritative for winner persistence.

Do not open TEMP if Frontier's safety latch trips, a protected/player-adjacent chunk is reclaimed, or the runtime reports an unrecoverable persistence error.
