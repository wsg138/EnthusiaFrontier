# Test2 TEMP deployment

Target: the existing Bloom `Test2` allocation, wiped completely before deployment.
Public Velocity name: `TEMP` so players can use `/server TEMP`.

## Panel resources

- Java: 21
- CPU: keep the existing 3200% allocation for initial testing
- Panel memory limit: 48 GB
- JVM heap: fixed 32 GB (`-Xms32G -Xmx32G`)
- Do not allocate more CPU/RAM unless live profiling shows an actual bottleneck

## JVM flags

Use the current main-SMP flags with the fixed Test2 heap:

```text
-Xms32G -Xmx32G -XX:+UseZGC -XX:+DisableExplicitGC -XX:+AlwaysPreTouch -XX:+ExitOnOutOfMemoryError -Xlog:gc*:logs/gc-zgc.log:time,uptime,level,tags:filecount=5,filesize=10M -Dterminal.jline=false -Dterminal.ansi=true -DLeaf.enableFMA=true -DLeaf.disable-vanilla-profiler=true -DLeaf.disable-vanilla-debug-feature=true -Duser.timezone=America/Indiana/Indianapolis
```

The committed `logs/` directory exists so the GC log path is valid on the first start.

## Preferred local preparation

On Lincoln's Windows machine, use the existing read-only `bloom-smp` rclone remote. From the repository root:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\prepare-test2-local.ps1 `
  -BackendPort <TEST2_BACKEND_PORT> `
  -CreateZip
```

This helper:

- creates an isolated deployment copy at `build/test2-runtime` instead of injecting secrets into tracked repository files;
- reads only the exact approved dependencies/configuration from the production SMP through `bloom-smp`;
- never runs rclone `sync`, `move`, `delete` or another production write operation;
- uses `server/prepare-test2.ps1` and the static validator against that isolated deployment copy;
- removes its temporary raw-SMP staging directory afterward;
- optionally creates `build/Test2-TEMP-runtime.zip` for upload.

The `build/` directory is gitignored. The produced runtime/archive contains private deployment material and must not be committed or shared publicly.

## Lower-level preparation

If a separate raw/current SMP root already exists locally, the lower-level helper can still be run inside an isolated copy of `server/`:

```powershell
.\prepare-test2.ps1 -SourceSmpRoot 'D:\path\to\raw-current-smp' -BackendPort <TEST2_BACKEND_PORT>
```

It resolves the current workflow-published Frontier JAR SHA from `BINARY-MANIFEST.yml`, requires the populate/validation helpers to already match that exact artifact, sets the TEMP server identity, runs `populate-runtime.ps1`, and requires the final static validator to pass. It does not rewrite deployment logic or hash pins locally.

Do not use the sanitized GitHub server snapshot as `SourceSmpRoot`; private forwarding/Floodgate/LuckPerms/Nexo values are intentionally absent there.

## Upload

After `TEST2_TEMP_RUNTIME_READY`, upload the **contents** of `build/test2-runtime/` into the completely empty Test2 root. Do not nest the `test2-runtime` directory itself. If the ZIP was created, upload/extract its contents at the Test2 root.

The first server start must use the committed Leaf `1.21.11-179` runtime and Java 21.

## Proxy

Patch the live Velocity and VeloTAB files with `velocity/apply-temp.ps1` using the real Test2 backend host/port. The patcher creates backups, removes stale `FRONTIER_TEST` aliases, creates exactly one `TEMP` backend, and refuses to put TEMP in the normal fallback list.

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
- all challenge/advancement/tag plugins load without errors

Do not open TEMP if Frontier's safety latch trips, a protected/player-adjacent chunk is reclaimed, or the runtime reports an unrecoverable persistence error.
