# EnthusiaFrontier

EnthusiaFrontier is a Leaf/Paper world-frontier manager for large SMP worlds. It separates the **usable world border** from the **pregenerated permanent core**, allowing Enthusia to expose much more land without pregenerating and permanently storing the entire expanded square.

The plugin has two independent safety systems:

1. **Adaptive generation throttling** — watches MSPT and changes Paper/Moonrise per-player generation limits before exploration pressure can materially damage tick performance.
2. **Frontier lifecycle management** — tracks newly generated chunks outside the permanent core, permanently protects chunks with real player activity, and can reclaim untouched frontier terrain after a retention period.

For production Enthusia the intended permanent overworld core is `100000` blocks in each horizontal direction. For disposable testing, `core-radius-blocks: 0` means **the entire world is managed by Frontier**.

## Cleanup safety

Destructive cleanup is implemented but intentionally **disabled by default**. Production rollout is expected to progress through tracking, dry-run review, disposable real-server acceptance, and only then explicit operator enablement.

The cleanup path is fail-closed:

- activity protection is monotonic and is installed in memory before its async SQLite mutation;
- SQLite commits a durable reclaim intent before Moonrise storage is modified;
- interrupted reclaim intents survive restart and are recovered before new candidates are reserved;
