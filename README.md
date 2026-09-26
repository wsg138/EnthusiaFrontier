# EnthusiaFrontier

EnthusiaFrontier is a Paper/Leaf plugin for safely managing expansion beyond a permanent pregenerated world core.

It tracks generated frontier chunks, permanently protects meaningful player activity, adapts runtime generation pressure, and supports conservative physical reclaim of untouched frontier storage through a fail-closed ledger and runtime adapter.

## Safety model

- The permanent square core is never managed by Frontier.
- Generated frontier chunks are recorded durably in SQLite.
- Player activity monotonically protects touched chunks plus a configurable neighbor radius.
- Destructive cleanup is opt-in, dry-run capable, and guarded by a persistent safety latch.
- Cleanup reserves durable reclaim intent before physical storage mutation.
- Region reclaim treats chunk, entity, and POI region storage as one logical operation.
- Paper generation controls are restored on plugin shutdown.
- Unsupported runtime internals fail closed when configured as required.

## Build

```bash
./gradlew clean check shadowJar
```

Java 21 is required. The current source compatibility baseline is Paper API 1.21.11.

## Testing

The JVM suite covers domain policy, persistence, mutation journaling, tracking, cleanup decisions, configuration validation, and reflective generation-throttle behavior. Destructive Moonrise/Paper storage behavior is gated by the isolated real-server acceptance workflow rather than mocked as safe.

See:

- `docs/SPECIFICATION.md`
- `docs/ARCHITECTURE.md`
- `docs/TESTING.md`
- `docs/FOUR-DAY-CLEANUP-TEST.md`

## Runtime status

`/frontier status` reports managed worlds, MSPT/throttle state, mutation queue health, safety-latch state, tracked chunk counts, and cleanup/storage-adapter status.

The repository also contains a disposable Frontier test-server package under `server/`; see `SERVER-RUNTIME.md` for the build/runtime manifest and staging notes.
