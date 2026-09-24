# EnthusiaFrontier — Complete Specification

## 1. Problem

Enthusia currently has a pregenerated square overworld core reaching roughly `-100000..+100000` blocks on X/Z. Pregenerating that space already consumes terabytes. Increasing the usable border by fully pregenerating the larger square scales with area and becomes impractical.

Frontier must allow a much larger border while meeting two non-negotiable goals:

1. exploration must not create enough simultaneous world generation to materially degrade the server; and
2. terrain that was only generated because somebody travelled through it must not become permanent storage forever.

The pregenerated/permanent radius and the actual Minecraft world border are intentionally independent.

## 2. Core concepts

### 2.1 Permanent core

Each configured world has `core-radius-blocks`.

- Production target: `100000` for the main overworld.
- Test target: `0`, which explicitly means **no permanent core; every chunk is managed by Frontier**.
- A chunk intersecting a positive-radius permanent core is never eligible for Frontier cleanup.
- Boundary decisions are conservative: if a chunk overlaps the permanent square at all, it is treated as permanent.

### 2.2 Managed frontier

A newly generated chunk outside the permanent core is recorded in the Frontier ledger as temporary.

Temporary does **not** mean immediately disposable. It means the chunk may become eligible after the retention period only if Frontier can prove that no persistent player activity protected it and all safety gates pass.

### 2.3 Player activity protection

The following classes of activity protect terrain permanently by default:

- block placement;
- block breaking;
- bucket/world modification;
- meaningful block interaction;
- container interaction;
- player-created persistent entities or structures when supported by the event layer;
- future integrations such as homes, claims, warps, portals, or server-specific base markers.

Activity protects the touched chunk plus a configurable chunk radius. Protection is monotonic by default: automatic cleanup never changes a protected chunk back to temporary.

A missed activity event is more dangerous than retaining extra terrain. Therefore every overload, persistence, compatibility, or uncertainty failure must bias toward **keeping data**.

## 3. Adaptive generation control

Frontier reads Paper's recent average MSPT and applies a configurable tiered generation policy.

Default concept:

| Band | Enter at | Per-player generation rate | Concurrent generations |
|---|---:|---:|---:|
| healthy | 0 ms | 32 chunks/s | 4 |
| elevated | 25 ms | 16 chunks/s | 3 |
| pressured | 35 ms | 8 chunks/s | 2 |
| critical | 45 ms | 2 chunks/s | 1 |

All numbers are configuration, not hard-coded product policy.

Requirements:

- use hysteresis so limits do not flap at a threshold;
- change limits only when the effective band changes;
- remember the server's pre-plugin generation settings and restore them on disable;
- fail startup when generation control is configured as required but the current Leaf/Paper internals cannot be safely adapted;
- never change general chunk loading limits merely to solve generation pressure;
- expose current MSPT, active band, rate, concurrency and adapter health in status output.

The initial adapter targets Paper/Leaf's `playerMaxChunkGenerateRate` and `playerMaxConcurrentChunkGenerates` runtime configuration. This internal dependency is isolated behind a port and compatibility-tested on real Leaf/Paper.

## 4. Persistent ledger

Frontier uses an embedded SQLite database in its plugin data directory.

For each managed chunk it stores at minimum:

- immutable world UUID;
- chunk X/Z;
- first observed generation timestamp;
- most recent persistent activity timestamp;
- protected flag;
- protection reason;
- deletion/reclamation timestamp where applicable.

Database requirements:

- WAL mode;
- schema versioning;
- transactional batched mutations;
- idempotent generation inserts;
- monotonic protection updates;
- no database work on the Minecraft tick thread during normal event traffic;
- bounded mutation buffering;
- persistent fail-closed safety latch if the mutation buffer overflows or durable writes become unreliable.

## 5. Cleanup safety model

Destructive cleanup is a separate capability from tracking. Tracking must be useful even while cleanup is disabled.

Cleanup defaults:

- `enabled: false` in the first production-capable release until acceptance is complete;
- dry-run before destructive mode;
- untouched retention configurable, initially expected around 30 days;
- maximum chunks/regions per cycle;
- minimum player distance;
- no loaded chunks;
- no chunks with tickets;
- no protected ledger entries;
- no deletion if the persistent safety latch is set;
- no deletion if the storage adapter cannot prove compatibility.

### 5.1 Logical chunk removal vs physical disk reclamation

Clearing an Anvil chunk slot is not sufficient evidence that disk usage was reclaimed; region files may retain allocated space. Frontier therefore distinguishes:

1. **logical reclaim** — remove CHUNK_DATA, ENTITY_DATA and POI_DATA so the terrain regenerates on next visit; and
2. **physical reclaim** — prove that filesystem allocation actually decreases or safely compact/delete an empty region container.

The storage goal is physical reclaim. A cleanup implementation is not considered complete merely because deleted chunks regenerate.

### 5.2 Region-level proof

Before physical reclamation, Frontier must prove the targeted region is safe using both its ledger and the actual on-disk region occupancy. Untracked occupied chunks are protected, not guessed disposable.

Raw region-file deletion while Paper/Leaf may have the file open is prohibited. Runtime adapters must use supported/synchronized server storage mechanisms or a controlled maintenance path with atomic replacement and crash recovery.

## 6. Failure behavior

Frontier follows a strict fail-closed rule for destructive operations.

Any of these disable cleanup while preserving tracking where possible:

- ledger queue overflow;
- SQLite transaction failure;
- unsupported Paper/Leaf storage internals;
- inconsistent world UUID/name mapping;
- on-disk occupancy that does not match tracked candidates;
- player/ticket/load state uncertainty;
- interrupted compaction/reclamation journal;
- failed integrity verification.

Generation throttling failure is separately visible. If `throttle.require-supported-adapter` is true, plugin enable must fail rather than silently run without the promised generation protection.

## 7. Operator interface

`/frontier status` must report:

- plugin version;
- managed worlds/core radii;
- recent average MSPT;
- current throttle band and limits;
- generation-adapter compatibility;
- mutation queue depth/health;
- persistent cleanup safety-latch state;
- tracked temporary/protected/deleted counts;
- cleanup mode once implemented.

Future destructive commands require explicit permissions and confirmation tokens. There will be no casual `/frontier delete` command.

## 8. Configuration guarantees

Configuration is validated on startup. Invalid safety-sensitive values are rejected rather than silently coerced.

`core-radius-blocks: 0` is a supported first-class test mode and must not be interpreted as disabled management.

World identity in persistence uses UUID, while configuration selects worlds by name. A world replacement with the same name but new UUID is treated as a different world.

## 9. Performance budget

- event handlers do constant/small bounded work on the tick thread;
- database writes occur off-thread in batches;
- no full-world or full-database scan on a tick thread;
- cleanup scans are bounded and resumable;
- generation-limit changes occur only on band transitions;
- status queries may perform bounded database reads but must not be used in hot paths.

## 10. Compatibility

Primary runtime: Enthusia Leaf on Minecraft 1.21.11 / Java 21.

The domain/application layers must not import Bukkit, Paper, Leaf, NMS, SQLite, or filesystem-specific types. Platform-specific behavior belongs in adapters so a future Paper/Leaf change replaces an adapter instead of rewriting policy.

## 11. Acceptance requirements

A release that enables destructive cleanup must prove all of the following:

- core radius `0` manages newly generated test-world chunks;
- positive core radius never schedules core/intersecting chunks for cleanup;
- activity protection survives restart;
- mutation overload latches cleanup unsafe and never loses that state across restart;
- throttle bands apply/recover with hysteresis and restore original Paper settings on disable;
- many simultaneous explorers do not produce unacceptable MSPT degradation under the test profile;
- untouched chunks can be logically regenerated after reclaim;
- protected chunks are byte/behavior preserved through cleanup runs;
- CHUNK_DATA, ENTITY_DATA and POI_DATA are handled consistently;
- real filesystem usage is reclaimed in physical-reclaim tests;
- crash/interruption tests never leave a world unrecoverably corrupt;
- exact-head Sentinel Sim and real Enthusia Staging checks pass.

## 12. Non-goals

- replacing the Minecraft world border;
- deleting the existing 100k pregenerated core;
- heuristic deletion of unknown old terrain;
- modifying production worlds before staging acceptance;
- pretending MockBukkit can validate Moonrise/NMS region storage.
