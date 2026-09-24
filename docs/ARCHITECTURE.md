# Architecture

EnthusiaFrontier uses hexagonal architecture so destructive storage behavior stays replaceable and policy remains independently testable.

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
- throttle levels and hysteresis;
- generation-limit value objects;
- cleanup candidate and reclamation outcomes.

## Application layer

Orchestrates use cases through ports:

- record observed generation;
- record player activity and expand protection radius;
- batch ledger mutations;
- sample MSPT and select a throttle band;
- apply/restore generation limits;
- require a durable cleanup reservation before destructive storage mutation;
- re-check runtime player/load/ticket/MSPT safety immediately before logical reclaim;
- trigger physical reclaim only after durable logical-deletion state exists.

Application services know interfaces, not Paper internals.

## Outbound ports

### FrontierRepository

Owns the durable SQLite ledger. Destructive mode atomically reserves eligible rows by writing `reclaim_intent_at_ms` before those candidates leave the repository. Existing intents are returned first after restart, so an interrupted operation is recoverable instead of becoming invisible.

Player protection and regeneration clear stale reclaim intent. Final deletion state is accepted only for an intended, unprotected row.

### GenerationThrottlePort

Applies/restores per-player chunk-generation limits. The production adapter isolates reflective Paper global-configuration access. Sentinel Sim uses a separate recording adapter and is not treated as evidence for production internals.

### ServerPerformancePort

Returns recent average MSPT. Production uses Paper's public average-tick-time API; simulation supplies a fixed sample only for lifecycle/policy plumbing.

### StorageReclaimPort

Owns logical chunk clear, storage flush, occupancy inspection, physical region reclaim and compatibility checks. No domain/application package manipulates `.mca` files or Moonrise internals.

The current Paper/Leaf adapter clears CHUNK_DATA, ENTITY_DATA and POI_DATA through Moonrise's synchronized `RegionDataController` write/delete path. Physical reclaim only unlinks a fully empty MCA container while holding the relevant Moonrise cache monitor and after proving the region is not open. Alternative/unknown formats fail closed.

## Inbound adapters

Bukkit listeners translate platform events into application commands. They never write SQLite directly. The command adapter exposes bounded diagnostics and the isolated acceptance entrypoint.

## Persistence threading

Normal event traffic submits immutable mutations into a bounded queue. A dedicated worker commits batches transactionally.

If the queue cannot accept a mutation, Frontier immediately marks cleanup unsafe. The persistent latch prevents any future destructive work until the condition is reviewed. Activity is also placed into an in-memory protection set before its async ledger write, closing the candidate-scan race window.

Cleanup candidate discovery/reservation runs asynchronously. World load/ticket/player-distance checks and Moonrise mutation run on the server thread in bounded per-tick work.

## Destructive storage ordering

The production ordering is intentionally asymmetric toward retaining data:

```text
TRACKED_TEMPORARY
 -> DURABLE_RECLAIM_INTENT   (SQLite commit, FULL synchronous)
 -> RUNTIME_SAFETY_RECHECK   (journal idle, MSPT, players, tickets, loads, activity)
 -> LOGICAL_CLEAR            (Moonrise chunk/entity/POI delete)
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

## Why generation throttling is global

Paper's generation limiter is a per-player global setting rather than a per-coordinate setting. In production, the permanent core is pregenerated, so changing the **generation** rate does not penalize normal movement through that core. In the zero-core staging profile, all exploration is intentionally subject to Frontier's generation policy.

## Compatibility boundary

Paper/Leaf internal configuration and Moonrise storage are expected to evolve. Exact class/field/method names stay inside adapters. Unsupported internals produce a visible compatibility failure, never a guessed reflective fallback.

## Extension points

- home/claim/warp protection providers;
- multiple cleanup retention classes;
- external SQL backend if SQLite becomes a bottleneck;
- Prometheus/Plan metrics adapter;
- Leaf-specific stable API adapter if Leaf exposes runtime generation controls;
- maintenance/offline compaction for future storage formats that cannot be safely reclaimed at runtime.
