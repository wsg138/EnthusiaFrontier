# EnthusiaTempChallenges — Frontier Test

Authoritative server-first challenge logic for the four-day Enthusia Frontier Test server.

## Architecture

- Challenge definitions are data-driven in `config.yml`.
- `ChallengeSignalListener` accepts event-oriented provenance: world entry, vanilla progression/location advancements, physical item pickup, physical-container transfer, crafting/smithing, armor completion following smithing, and credited entity final kills. Joining or inventory scanning never creates a claim.
- `OrderedChallengeProcessor` and `JdbcChallengeLedger` persist an ordered attempt journal and the unique winner in a single SQLite transaction. `(event_id, challenge_id)` is the database-enforced one-winner boundary and `(event_id, challenge_id, signal_id)` is the replay boundary.
- A unique winner transaction commits before announcement, XP, LuckPerms, tags, or custom advancements.
- Reward delivery state is durable and can be reconciled after restart. LuckPerms ownership checks inspect an exact direct positive global user node; Bukkit wildcard/OP permission resolution is never treated as entitlement ownership.
- Floodgate identities use `FloodgatePlayer#getCorrectUniqueId()` so linked Bedrock/Java sessions use the canonical linked identity rather than username-prefix heuristics.
- Dragon damage is tracked per dragon and persisted at death; only Bukkit's credited final killer can claim the primary Dragon first.
- `first_elytra` exists in both the challenge registry and presentation tree but is locked in configuration, so an operator-created Elytra cannot claim it.

## Persistence and recovery

Authoritative local state is `plugins/EnthusiaTempChallenges/challenge-ledger.sqlite`, configured with SQLite WAL, `synchronous=FULL`, foreign keys and schema-version metadata. Portable winner state is represented by exact LuckPerms nodes such as `enthusia.frontier.first.diamonds`. `/tempchallenge export` writes a human-readable winners CSV.

The plugin records reward projection state separately from the winner row. If tags, advancements or LuckPerms are unavailable after a durable claim, the local winner remains valid and `/tempchallenge reconcile <player>` or player join can restore projections from the local winner/LuckPerms entitlement.

## Administrative safety

`/tempchallenge test <id> <player>` is deliberately dry-run only. `/give`, `/advancement grant`, and `/item replace entity` apply a short mutation guard to online targets. Selectors fail closed by temporarily guarding all online players. Creative, spectator, operators (by default), the configured exclusion permission and guarded mutations cannot win. `revoke-first` requires the currently expected winner UUID.

## Build target

- Java 21
- Paper API 1.21.11
- SQLite JDBC 3.53.4.0 (shaded)
- LuckPerms API 5.4 (server-provided)
- Floodgate API 2.2.5-SNAPSHOT (server-provided)

Run `mvn -B clean verify package` from this directory.

## Proof-binary provenance limitation

The requested `EnthusiaTempChallenges-0.1.0-proof.1.jar` was not present in the current conversation file mount, user Library search, `wsg138/EnthusiaFrontier`, or the earlier Frontier prepared-server repository when this source was reconstructed. This implementation therefore preserves and strengthens the architecture described for that proof — durable first state, ordered journal, eligibility, Dragon contribution/final-hit semantics, entitlements, projection, review/export and restart recovery — without claiming bytecode equivalence to an unavailable binary.
