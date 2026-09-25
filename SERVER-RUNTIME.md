# Frontier Test runtime layout

The deployable Minecraft root is `server/`. The proxy-only additions are in `velocity/`.

This replaces the older installer/template-oriented layout in `wsg138/Enthusia-Server` for the Frontier test. The normal runtime branch is `server/frontier-test-runtime`.

Leaf target: 1.21.11 build 179 / Java 21. Secure Seed is enabled in `server/config/leaf-global.yml`.

The challenge worker may add/update the challenge/advancement JARs and configs under `server/plugins/`; preserve that work when reconciling this branch.
