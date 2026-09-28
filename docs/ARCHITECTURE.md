# Architecture

EnthusiaFrontier uses hexagonal architecture so destructive storage behavior and generation admission stay replaceable while policy remains independently testable.

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

- `ChunkKey` and `RegionKey`;
- permanent-core boundary semantics;
- activity/protection semantics;
- generation-shield levels, whole-server limits and MSPT hysteresis;
- secondary Paper throttle levels and generation-limit value objects;
- cleanup candidate and reclamation outcomes.

## Application layer

Orchestrates use cases through ports:

- record observed generation;
- record player activity and expand protection radius;
- batch ledger mutations;
- sample MSPT and select global generation-shield limits;
- admit virgin generation through one bounded requester-fair whole-server queue;
- deduplicate identical chunk requests across requesters;
- build and refresh generated safety buffers away from movement hot paths;
- apply/restore secondary Paper per-player generation limits;
- require a durable cleanup reservation before destructive storage mutation;
- re-check runtime player/load/ticket/MSPT safety immediately before logical reclaim;
- trigger physical reclaim only after durable logical-deletion state exists.

Application services know interfaces, not Paper internals.

## Generation Shield

`GenerationShieldService` is the primary runtime generation safety mechanism. It owns one server-wide queue and one server-wide budget regardless of requester count.

Its invariants are:

- the configured chunks-per-second budget is aggregate across all explorers/requesters;
- `maxConcurrent` is aggregate across the whole server;
- duplicate requests for the same chunk collapse globally;
- requester queues rotate fairly so one explorer cannot monopolize admission;
- the pending queue is bounded and rejects overload explicitly;
- critical limits of `0 chunks/s` or `0 concurrent` mean **zero new Frontier generation admissions**;
- existing in-flight async work is allowed to complete, but no new work is submitted while paused;
- readiness is not considered complete until generation succeeds and the generated state is durably committed;
- any generation/readiness failure makes the shield unhealthy and future frontier generation fails closed.

`GenerationShieldController` samples average MSPT and uses hysteresis to select the active whole-server limits. Starting values in configuration are staging inputs, not production-performance claims.

### Generated safety buffer

`GenerationBufferCoordinator` prepares a square of proven/generated terrain around a requested movement center. It does not synchronously rescan a large radius on every movement packet: the movement path reuses the current buffer state, while a bounded scheduler refreshes pending readiness.

The required runtime radius is:

```text
max(player view distance, player send-view distance, player simulation distance)
+ configured safety margin
```

If that required radius exceeds the configured maximum, movement farther into managed frontier terrain is blocked rather than silently truncating the safety buffer.

The Bukkit listener guards normal movement, player teleports, portals, player-riding entity teleports and vehicle movement/rollback paths. This is a defense against Paper beginning generation around a player before the player physically enters an ungenerated destination chunk.

### Readiness persistence

`SqliteGenerationReadinessAdapter` keeps movement/readiness lookups memory-only while persisting successful generation to the same SQLite `frontier_chunk` ledger on a serial writer with WAL and `synchronous=FULL`.

A chunk becomes ready only after the durable write completes. Startup reloads durable ready rows and can adopt already-loaded managed chunks without generating them. Logical cleanup invalidates the hot readiness cache immediately, so reclaimed terrain cannot be treated as ready in the same process.

## Outbound ports

### FrontierRepository

Owns the durable SQLite ledger. Destructive mode atomically reserves eligible rows by writing `reclaim_intent_at_ms` before those candidates leave the repository. Existing intents are returned first after restart, so an interrupted operation is recoverable instead of becoming invisible.

Player protection and regeneration clear stale reclaim intent. Final deletion state is accepted only for an intended, unprotected row.

### ChunkGenerationPort / GenerationReadinessPort

The production generation adapter submits non-urgent asynchronous Paper/Leaf chunk generation. The application layer never calls Bukkit directly. Readiness is a separate durable boundary so an async generation future completing is not enough by itself to allow movement.

### GenerationThrottlePort

Applies/restores Paper/Moonrise per-player chunk-generation limits. This is **secondary defense in depth**, not Frontier's aggregate generation budget. The production adapter isolates reflective Paper global-configuration access.

Paper's rate is per player and therefore scales with explorer count; additionally, Paper rate `0` is not a true pause. Frontier never relies on that adapter to provide the server-wide budget or critical zero-admission state.

### ServerPerformancePort

Returns recent average MSPT. Production uses Paper's public average-tick-time API; simulation supplies a fixed sample only for lifecycle/policy plumbing.

### StorageReclaimPort

Owns logical chunk clear, storage flush, occupancy inspection, physical region reclaim and compatibility checks. No domain/application package manipulates `.mca` files or Moonrise internals.

The current Paper/Leaf adapter clears CHUNK_DATA, ENTITY_DATA and POI_DATA through Moonrise's synchronized `RegionDataController` write/delete path. Physical reclaim only unlinks a fully empty MCA container while holding the relevant Moonrise cache monitor and after proving the region is not open. Alternative/unknown formats fail closed.

## Inbound adapters

Bukkit listeners translate platform events into application commands. They never write SQLite directly. The command adapter exposes bounded diagnostics plus isolated acceptance/load-test entrypoints that are fenced to the disposable loopback Sentinel-style runtime.

## Persistence threading

Normal event traffic submits immutable mutations into a bounded queue. A dedicated worker commits batches transactionally.

If the queue cannot accept a mutation, Frontier immediately marks cleanup unsafe. The persistent latch prevents any future destructive work until the condition is reviewed. Activity is also placed into an in-memory protection set before its async ledger write, closing the candidate-scan race window.

Generation readiness uses a separate serial writer because movement must remain a memory-only read path. The global generation service retains its concurrency slot until that durable readiness future completes.

Cleanup candidate discovery/reservation runs asynchronously. World load/ticket/player-distance checks and Moonrise mutation run on the server thread in bounded per-tick work.

## Destructive storage ordering

The production ordering is intentionally asymmetric toward retaining data:

```text
TRACKED_TEMPORARY
 -> DURABLE_RECLAIM_INTENT   (SQLite commit, FULL synchronous)
 -> RUNTIME_SAFETY_RECHECK   (journal idle, MSPT, players, tickets, loads, activity)
 -> LOGICAL_CLEAR            (Moonrise chunk/entity/POI delete)
 -> READINESS_INVALIDATION   (same-process hot cache)
 -> DURABLE_DELETED          (async journal commit)
 -> EMPTY_REGION_PROOF
 -> PHYSICAL_RECLAIM         (cache-closed MCA unlink)
```

Important crash cases:

- crash before intent: world is untouched;
- crash after intent but before clear: intent is recovered and re-checked after restart;
- crash after clear but before final deleted marker: the durable intent still identifies the interrupted operation; a persistence failure trips the cleanup latch;
- crash after final deleted marker but before physical reclaim: region reclaim can be retried later;
- any unknown occupancy or open storage handle prevents physical unlink.

A region containing any occupied unknown/protected data is not physically reclaimable.

## Why the generation budget is truly global

Paper/Moonrise's exposed generation-rate and concurrency settings are per-player. They remain useful defense in depth, but multiplying a per-player budget across many explorers does not protect the server as a whole.

Frontier therefore admits virgin terrain through its own global queue before asking Paper/Leaf to generate it. A configured budget such as `8 chunks/s, 4 concurrent` remains one budget with 1, 10, 20 or 40 requesters. The real-Leaf CI harness verifies that invariant through the production queue and async generation adapter.

The permanent core bypasses Frontier generation admission because it is already generated. In a zero-core disposable test profile, essentially all new exploration is managed by the shield.

## Compatibility boundary

Paper/Leaf internal configuration and Moonrise storage are expected to evolve. Exact class/field/method names stay inside adapters. Unsupported internals produce a visible compatibility failure, never a guessed reflective fallback.

## Extension points

- home/claim/warp protection providers;
- multiple cleanup retention classes;
- external SQL backend if SQLite becomes a bottleneck;
- Prometheus/Plan metrics adapter;
- Leaf-specific stable API adapter if Leaf exposes runtime generation controls;
- a real/headless Minecraft-client staging driver for movement and network-load validation;
- maintenance/offline compaction for future storage formats that cannot be safely reclaimed at runtime.
