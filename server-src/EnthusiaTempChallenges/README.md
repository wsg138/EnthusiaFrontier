# EnthusiaTempChallenges — Frontier Test

Authoritative server-first challenge logic for the four-day Enthusia Frontier Test server.

## Architecture

- Challenge definitions are data-driven in `config.yml`.
- `ChallengeSignalListener` accepts event-oriented provenance only. World entry, vanilla advancements, trusted block/entity drops, non-plugin loot-table output, crafting, smithing, furnace extraction, armor completion after smithing, and credited entity final kills can produce evidence. Joining, inventory scanning, ordinary chest storage moves, player-to-player handoffs, and arbitrary restored/dropped items do not create claims.
- Natural challenge-item drops receive a short-lived persistent provenance marker while they are still at their original gameplay source. The marker is consumed on the first trusted pickup/loot transfer so normal player inventory items do not remain specially tagged. Staff-created and invalid-origin items use a separate persistent rejection marker.
- Command/plugin/spawn-egg-created mobs are marked invalid. Their drops and Dragon/Wither final blows cannot create Frontier firsts. Normal spawner, trial-spawner, raid, natural, and player-built Wither gameplay remains eligible subject to the normal player eligibility policy.
- `OrderedChallengeProcessor` and `JdbcChallengeLedger` persist an ordered attempt journal and the unique winner in one SQLite transaction. `(event_id, challenge_id)` is the database-enforced one-winner boundary and `(event_id, challenge_id, signal_id)` is the replay boundary.
- The durable winner transaction commits before announcement, XP, LuckPerms, tags, or custom advancements.
- Reward delivery state is separate from winner authority and can be reconciled after restart.
- XP is crash-idempotent: the XP award and a per-challenge marker are persisted together in Bukkit player data before SQLite delivery is acknowledged. A retry after a crash sees the marker and does not pay again.
- LuckPerms portable ownership requires an exact direct positive context-free user node. Wildcards, inherited permissions, OP state, and Bukkit permission resolution are not treated as proof of ownership.
- LuckPerms and Floodgate are true runtime soft dependencies. The plugin uses their live APIs reflectively when present; local SQLite authority continues to function when either is unavailable, with portable reward work remaining pending.
- Floodgate identity resolution uses the runtime equivalent of `FloodgatePlayer#getCorrectUniqueId()` / `getCorrectUsername()` so linked Bedrock/Java sessions use canonical linked identity rather than username-prefix heuristics.
- Dragon damage is tracked per dragon and persisted at death. Only Bukkit's credited final killer can claim the primary Dragon first; a credited killer missing from the damage map still gets an auditable zero-damage final-blow row.
- `first_elytra` exists in the registry/presentation tree but is locked in configuration because Elytras are intentionally unobtainable on Frontier Test.

## Persistence and recovery

Authoritative local state is `plugins/EnthusiaTempChallenges/challenge-ledger.sqlite`, configured with SQLite WAL, `synchronous=FULL`, foreign keys, a busy timeout, and schema-version metadata. The plugin refuses to open a ledger created by a newer unsupported schema instead of silently downgrading metadata.

Portable winner state is represented by exact LuckPerms nodes such as `enthusia.frontier.first.diamonds`. `/tempchallenge export` writes a human-readable winners CSV.

The plugin records reward projection state separately from the winner row. If tags, advancements or LuckPerms are unavailable after a durable claim, the local winner remains authoritative. `/tempchallenge reconcile <player>` and delayed join reconciliation restore local-winner rewards plus presentation implied by an explicit portable entitlement. Portable entitlement alone never re-pays local XP on another backend.

## Administrative safety

- `/tempchallenge test <id> <player>` is dry-run only and cannot create a winner.
- `/tempchallenge award-first <id> <online-player> CONFIRM <reason>` is the explicit audited staff override. It still respects locked challenges, closed events, and the unique-winner transaction.
- `/give`, `/advancement grant`, `/item replace entity`, creative inventory mutations, and common transformations of invalid-origin items are excluded from normal first evidence. Invalid item provenance follows drops, containers, crafting, smithing, and smelting.
- Creative, spectator, operators by default, the configured exclusion permission, and players inside the short command-mutation guard cannot win through normal gameplay signals.
- `/tempchallenge revoke-first <id> <expected-winner-uuid>` requires the currently recorded winner UUID. It revokes portable/presentation projections; previously granted XP is intentionally not subtracted, and its durable marker remains so the same player cannot be paid twice later.

## Build target

- Java 21
- Paper API 1.21.11
- SQLite JDBC 3.53.4.0, shaded into the challenge JAR
- UltimateAdvancementAPI 2.8.1 source commit `67d9576ae5e4ec55701ac653194bb77c5f1e708c`, built only with its Minecraft 1.21.11 (`1_21_R7`) NMS adapter
- BadgersMC/EnthusiaAdvancements source `42e901473234f5b69c07d5416565d80addbb197d`
- EnthusiaTags source `261efb9144216ae86a4a2c8ed406a7f44dcae851`
- LuckPerms and Floodgate APIs are server-provided optional runtime integrations, not shaded or required to load the local ledger

For the challenge plugin alone, run `mvn -B clean verify` from this directory. The repository build workflow uses `tools/build-frontier-challenges.sh` to build the complete pinned runtime, normalize JAR metadata for reproducible hashes, validate plugin descriptors, run the real Leaf 1.21.11 startup smoke, and publish the exact deployable binaries only if challenge source has not changed underneath the run.

## Proof-binary provenance limitation

The requested `EnthusiaTempChallenges-0.1.0-proof.1.jar` was not present in the available conversation files, Library search, `wsg138/EnthusiaFrontier`, or the earlier Frontier prepared-server repository when this source was reconstructed. This implementation therefore preserves and strengthens the documented proof architecture without claiming bytecode equivalence to an unavailable binary.
