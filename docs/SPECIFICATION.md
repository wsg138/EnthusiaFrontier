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

## 3. Generation safety

Frontier must not claim to make Minecraft world generation itself faster. Paper/Leaf/Moonrise already own generation implementation and scheduling. Frontier's job is to bound **when and how much new virgin terrain is admitted**, sacrificing exploration speed before server health.

### 3.1 Primary: whole-server Generation Shield

All Frontier-owned virgin terrain generation outside the permanent core is admitted through one server-global bounded queue.

Required invariants:

- chunks-per-second is one whole-server budget, not one budget per player;
- maximum concurrent generation is one whole-server limit;
- identical chunk requests from different explorers deduplicate globally;
- scheduling is fair across requesters so one fast explorer cannot monopolize the queue;
- the pending queue is bounded and overload is explicit;
- generation completion does not make a chunk safe until readiness is durably persisted;
- any generation/readiness integrity failure makes the shield unhealthy and new frontier generation fails closed;
- MSPT hysteresis changes the aggregate budget by band;
- critical pressure supports a true `0 chunks/s, 0 concurrent` state, meaning **zero new Frontier generation admissions**;
- already-running async generation jobs do not need unsafe cancellation, but no new jobs are submitted while paused;
- permanent-core chunks bypass the shield because they are already generated.

Current staging starting points are deliberately conservative and are **not** production-calibrated claims:

| Band | Enter at | Whole-server rate | Whole-server concurrent |
|---|---:|---:|---:|
| healthy | 0 ms | 8 chunks/s | 4 |
| elevated | 25 ms | 4 chunks/s | 3 |
| pressured | 35 ms | 2 chunks/s | 2 |
| critical | 45 ms | 0 chunks/s | 0 |

All values remain configuration.

### 3.2 Generated movement buffer

Blocking only entry into an ungenerated destination chunk is insufficient because Paper can generate chunks around the player's current position according to runtime view/generation distances.

Before movement farther into managed frontier terrain is allowed, Frontier requires a generated/readiness buffer around the destination. The required radius is based on:

```text
max(player view distance, player send-view distance, player simulation distance)
+ configured safety margin
```

Requirements:

- normal movement checks must not synchronously scan a large radius on every movement packet;
- readiness should use cached/persistent state and bounded scheduled refresh work;
- teleports, portals, player-riding entity teleports, vehicles and high-speed movement paths must fail closed where unsafe;
- if the runtime-required radius exceeds the configured maximum guard radius, Frontier must restrict further frontier advancement instead of silently truncating the safety buffer;
- while critical/unhealthy, players may continue within already-safe generated terrain, but new frontier advancement must stop.

### 3.3 Secondary: Paper/Moonrise per-player throttle

Frontier also adapts Paper's runtime `playerMaxChunkGenerateRate` and `playerMaxConcurrentChunkGenerates` settings as defense in depth.

These controls are **per player**, so they cannot be the primary whole-server budget: more explorers multiply the aggregate allowance. Paper's generation rate `0` also does not provide the required true pause behavior, so Frontier never uses it as the critical stop mechanism.

Secondary-throttle requirements:

- use hysteresis so limits do not flap at a threshold;
- change limits only when the effective band changes;
- remember the server's pre-plugin generation settings and restore them on disable;
- fail startup when this compatibility layer is configured as required but the current Leaf/Paper internals cannot be safely adapted;
- never change general chunk-loading limits merely to solve generation pressure;
- expose current MSPT, active bands, aggregate shield rate/concurrency, queue state and adapter health in status output.

## 4. Persistent ledger and generation readiness

Frontier uses an embedded SQLite database in its plugin data directory.

For each managed chunk it stores at minimum:

- immutable world UUID;
- chunk X/Z;
- first observed generation timestamp;
- most recent persistent activity timestamp;
- protected flag;
- protection reason;
- durable reclaim-intent timestamp where applicable;
- deletion/reclamation timestamp where applicable.

The same ledger also provides restart-safe generated readiness for the Generation Shield. Movement/readiness checks stay memory-only; successful async generation is persisted on a dedicated serial writer before its shield concurrency slot is released.

Database requirements:

- WAL mode;
- full synchronous durability for safety-critical commits;
- schema versioning/migration;
- transactional batched mutations;
- idempotent generation inserts;
- monotonic protection updates;
- no database work on the Minecraft tick thread during normal event traffic;
- bounded mutation buffering;
- restart-safe generated readiness;
- immediate hot-cache invalidation after logical reclaim;
- persistent fail-closed safety latch if the mutation buffer overflows or durable writes become unreliable.

## 5. Cleanup safety model

Destructive cleanup is a separate capability from tracking. Tracking remains useful while cleanup is disabled.

Cleanup defaults:

- `enabled: false` and `dry-run: true` in the production configuration;
- disposable real-server acceptance does not automatically authorize production deletion;
- untouched retention configurable, initially expected around 30 days;
- maximum chunks/regions per cycle;
- minimum player distance;
- no loaded chunks;
- no chunks with tickets;
- no protected ledger entries;
- no deletion if the persistent safety latch is set;
- no deletion if the storage adapter cannot prove compatibility;
- a durable SQLite reclaim intent must commit before the first Moonrise storage mutation.

### 5.1 Logical chunk removal vs physical disk reclamation

Clearing an Anvil chunk slot is not sufficient evidence that disk usage was reclaimed; region files may retain allocated space. Frontier therefore distinguishes:

1. **logical reclaim** — remove CHUNK_DATA, ENTITY_DATA and POI_DATA so the terrain regenerates on next visit; and
2. **physical reclaim** — prove that filesystem allocation actually decreases or safely delete an empty region container.

The storage goal is physical reclaim. A cleanup implementation is not considered complete merely because deleted chunks regenerate.

### 5.2 Region-level proof

Before physical reclamation, Frontier must prove the targeted region is safe using both its ledger and actual on-disk region occupancy. Untracked occupied chunks are protected, not guessed disposable.

Raw region-file deletion while Paper/Leaf may have the file open is prohibited. Runtime adapters must use supported/synchronized server storage mechanisms or a controlled maintenance path with crash-safe ordering.

### 5.3 Crash ordering

Destructive ordering is:

1. commit durable reclaim intent;
2. re-check runtime safety;
3. logically clear chunk/entity/POI data;
4. immediately invalidate same-process generated readiness;
5. durably mark deletion;
6. prove the whole region container is empty and not open;
7. physically reclaim the empty container.

Interrupted intents survive restart and are recovered before new destructive candidates are reserved. If durable finalization fails after logical clear, cleanup latches unsafe instead of continuing.

## 6. Failure behavior

Frontier follows a strict fail-closed rule.

Any of these disable destructive cleanup while preserving data:

- ledger queue overflow;
- SQLite transaction failure;
- unsupported Paper/Leaf storage internals;
- inconsistent world UUID/name mapping;
- on-disk occupancy that does not match tracked candidates;
- player/ticket/load state uncertainty;
- invalid/missing durable reclaim intent;
- failed integrity verification.

Generation safety also fails closed. These conditions stop new managed-frontier generation/admission rather than allowing uncontrolled exploration:

- global shield/readiness persistence failure;
- persistent safety latch;
- unhealthy mutation journal where readiness/tracking integrity is uncertain;
- critical MSPT policy selecting `0/0`;
- runtime guard radius larger than the configured safe maximum;
- queue/controller state that cannot be trusted.

If `throttle.require-supported-adapter` is true, loss of the secondary Paper throttle compatibility layer still fails plugin enable rather than silently omitting the configured defense in depth.

## 7. Operator interface

`/frontier status` reports:

- plugin version;
- managed worlds/core radii;
- recent average MSPT;
- secondary Paper throttle band and limits;
- active global shield band/rate/concurrency;
- global queue depth, in-flight count, started/completed/deduplicated/rejected counters;
- pending generated buffers and readiness-cache size;
- generation-adapter compatibility;
- mutation queue depth/health;
- persistent cleanup safety-latch state;
- tracked temporary/protected/deleted counts;
- cleanup mode and bounded cleanup counters.

There is no casual production `/frontier delete` command. Destructive acceptance and generation-load test entrypoints require exact confirmation tokens and are additionally fenced to isolated loopback Sentinel-style runtime conditions.

## 8. Configuration guarantees

Configuration is validated on startup. Invalid safety-sensitive values are rejected rather than silently coerced.

`core-radius-blocks: 0` is a supported first-class test mode and must not be interpreted as disabled management.

World identity in persistence uses UUID, while configuration selects worlds by name. A world replacement with the same name but new UUID is treated as a different world.

Generation Shield limits are explicitly whole-server limits. Paper throttle limits are explicitly per-player defense in depth. Configuration comments/documentation must not conflate the two.

## 9. Performance budget

- event handlers do constant/small bounded work on the tick thread;
- database writes occur off-thread in batches/serial readiness commits;
- no full-world or full-database scan on a tick thread;
- no large synchronous guard-radius scan on every movement packet;
- global generation admission is bounded by rate, concurrency and queue capacity;
- cleanup scans/reservations are bounded and asynchronous;
- destructive world/storage work is bounded per tick;
- generation-limit changes occur only on band transitions;
- status queries may perform bounded database reads but must not be used in hot paths.

## 10. Compatibility

Primary runtime: Enthusia Leaf on Minecraft 1.21.11 / Java 21.

The domain/application layers must not import Bukkit, Paper, Leaf, NMS, SQLite, or filesystem-specific types. Platform-specific behavior belongs in adapters so a future Paper/Leaf change replaces an adapter instead of rewriting policy.

## 11. Acceptance requirements

Before production deployment, evidence must cover generation safety independently from destructive cleanup.

Generation evidence:

- one aggregate rate/concurrency budget remains bounded with 1, 10, 20 and 40 requesters;
- 40 requesters cannot multiply the configured server-wide generation rate;
- duplicate chunk requests collapse;
- bounded queue overload is explicit;
- critical policy admits zero new Frontier generation work;
- readiness is durable before an in-flight slot is released;
- exact produced JAR boots on real Leaf 1.21.11, status works, shuts down cleanly and reopens the same SQLite/world state;
- real Leaf async chunk generation through the production adapter passes an isolated 1/10/20/40 synthetic-requester suite;
- real/headless-client staging still validates actual movement, teleports/vehicles, network timing, movement delay and representative production load before final calibration;
- production hardware testing measures MSPT p50/p95/p99, TPS, CPU, memory/GC, generation latency, queue backlog and player edge delay.

Cleanup evidence:

- core radius `0` manages newly generated test-world chunks;
- positive core radius never schedules core/intersecting chunks for cleanup;
- activity protection survives restart;
- mutation overload latches cleanup unsafe and never loses that state across restart;
- secondary Paper throttle bands apply/recover with hysteresis and restore original settings on disable;
- untouched chunks can be logically regenerated after reclaim;
- protected chunks are behaviorally preserved through cleanup runs;
- CHUNK_DATA, ENTITY_DATA and POI_DATA are handled consistently;
- real region-file usage is reclaimed in physical-reclaim tests;
- interrupted reclaim intent is recoverable across restart and persistence failure fails closed;
- exact-head hosted CI/artifact and real Enthusia Sentinel/Staging checks pass.

The CI runner's load numbers are functional evidence, not production calibration. Production tuning requires Enthusia hardware and representative plugin/player load.

## 12. Non-goals

- replacing the Minecraft world border;
- making Paper/Leaf world generation itself faster;
- guaranteeing zero TPS/MSPT impact from runtime generation;
- deleting the existing 100k pregenerated core;
- heuristic deletion of unknown old terrain;
- modifying production worlds during acceptance;
- pretending MockBukkit or synthetic requester IDs can validate real-client movement/network behavior;
- treating GitHub-hosted runner performance as Enthusia production capacity.
