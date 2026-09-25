# Frontier Test runtime layout

The deployable Minecraft root is `server/`. The proxy-only additions are in `velocity/`.

This replaces the older installer/template-oriented layout in `wsg138/Enthusia-Server` for the Frontier test. The normal runtime branch is `server/frontier-test-runtime`.

Runtime baseline:

- Leaf 1.21.11 build 179
- Java 21
- Leaf Secure Seed enabled
- Overworld `-5000..+5000`
- Nether `-2500..+2500`
- End `-2500..+2500`
- no pregeneration
- no obtainable Elytras
- real one-day Frontier cleanup in the Overworld

The finalized challenge runtime is committed under `server/plugins/`, including EnthusiaTempChallenges, EnthusiaAdvancements, UltimateAdvancementAPI and the Frontier-specific EnthusiaTags build. Preserve those committed artifacts/configs; `server/populate-runtime.ps1` intentionally does not replace them with older SMP copies.

The normal server structure, runtime binary manifest, Velocity patch, first-boot checklist and static validator are all source-controlled on this branch. Private network credentials/keys remain deployment-only and are populated from a raw current SMP server root.
