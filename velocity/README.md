# Velocity additions for Enthusia Frontier Test

Do not replace the live proxy with this directory. Apply the included backend fragment/group to the existing Velocity installation.

Backend key: `FRONTIER_TEST`

- point it at the actual EnthusiaState backend allocation
- keep it OUT of the normal `try = ["HUB", "SMP"]` fallback chain
- use the existing network forwarding secret; never commit it
- VeloTAB gets a dedicated plain-text group so it has no guild/economy/rep/Nexo placeholder dependency
