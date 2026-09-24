# Architecture

EnthusiaFrontier uses hexagonal architecture (ports and adapters). The goal is not architectural ceremony; it is to keep destructive world-storage code replaceable and keep policy independently testable.

## Dependency rule

Dependencies point inward:

```text
Leaf / Paper / Bukkit events        SQLite / filesystem / Moonrise
            |                                  |
            v                                  v
      inbound adapters                    outbound adapters
             \                              /
              \                            /
               v                          v
                    application services
                           |
                           v
                         domain
```

The domain has no Bukkit/Paper/Leaf/SQLite imports.

## Domain

Owns stable concepts and pure rules:

- `ChunkKey`
- permanent-core boundary semantics
- activity/protection semantics
- throttle levels and hysteresis
- generation-limit value objects
- cleanup eligibility/reclamation state as later milestones land

Pure policy is tested without a Minecraft server.

## Application layer

Orchestrates use cases through ports:

- record observed generation;
- record player activity and expand protection radius;
- batch journal mutations;
- sample MSPT and select a throttle band;
- apply generation limits through a platform port;
- later: plan cleanup, verify eligibility, execute reclaim, journal recovery.

Application services know interfaces, not Paper internals.

## Outbound ports

### FrontierRepository

Durable ledger operations. Initial adapter: SQLite.

### GenerationThrottlePort

Applies/restores per-player chunk-generation limits. Initial adapter: reflective Paper global configuration. Reflection is contained here because these fields are not a stable Bukkit API.

### ServerPerformancePort

Returns recent average MSPT. Initial adapter uses Paper's public `Server#getAverageTickTime()`.

### ChunkStoragePort (cleanup milestone)

Will own logical chunk clear, storage flush, physical region reclaim and compatibility checks. No other package may manipulate `.mca` files or Moonrise internals.

## Inbound adapters

Bukkit listeners translate platform events into application commands. They do not write SQLite directly and they do not contain policy.

The Bukkit command adapter exposes bounded diagnostics/admin controls.

## Persistence threading

The event thread submits immutable mutations into a bounded queue. One dedicated worker drains mutations in batches and commits them transactionally.

If the queue cannot accept a mutation, Frontier immediately marks its in-memory cleanup state unsafe. The worker persists the safety latch before any future destructive capability is allowed. This is important because losing an activity event could otherwise turn a real base into an apparently untouched chunk.

## Compatibility boundary

Paper/Leaf internal configuration and Moonrise storage are expected to evolve. Their exact class/field/method names must not leak into domain/application code.

Adapters perform explicit startup probes. Unsupported internals produce a visible compatibility failure, never a guessed reflective fallback.

## Destructive storage design

Physical reclamation will use a journaled state machine rather than a one-shot delete:

```text
DISCOVERED
 -> VERIFIED_ELIGIBLE
 -> QUIESCED
 -> LOGICALLY_CLEARED
 -> PHYSICALLY_RECLAIMED
 -> VERIFIED
```

A crash at any step must be detectable and resumable/repairable. Reclaim journals are durable before the first destructive write.

A region containing any unknown occupied chunk is not physically reclaimable.

## Why generation throttling is global

Paper's generation limiter is a per-player global setting rather than a per-coordinate setting. In production, the permanent core is pregenerated, so changing the **generation** rate does not penalize normal movement through that core. In the zero-core staging profile, all exploration is intentionally subject to Frontier's generation policy.

## Future extension points

- home/claim/warp protection providers;
- multiple cleanup retention classes;
- external SQL backend if SQLite ever becomes a scale bottleneck;
- Prometheus/Plan metrics adapter;
- Leaf-specific stable API adapter if Leaf exposes runtime generation controls;
- offline/maintenance region compactor if runtime physical reclaim cannot be proven safe.
