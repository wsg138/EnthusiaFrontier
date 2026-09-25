# Enthusia Frontier Test server root

This directory is intentionally laid out like the root of a normal Leaf/Paper Minecraft server. Upload/copy the contents of `server/` directly into the Pterodactyl server root.

## World
- Overworld: 5000 x 5000
- Nether: 2500 x 2500
- End: 1000 x 1000 (main island only)
- End enabled immediately
- no pregeneration
- fixed fresh seed for reproducible stronghold placement
- Leaf Secure Seed enabled
- Frontier cleanup: real deletion after 1 untouched day

## Before public opening
The following secret/binary values must be copied from the live network and are deliberately not committed:
1. `config/paper-global.yml` Velocity forwarding secret
2. `plugins/floodgate/key.pem`
3. LuckPerms MariaDB host/database/user/password
4. Nexo live resource-pack assets and Polymath hosting secret
5. the plugin JARs listed in `BINARY-MANIFEST.yml`

`start.sh` automatically downloads and verifies the official Leaf 1.21.11 build 179 if the Leaf JAR is absent.

The separate `velocity/` directory in the repository contains the proxy backend and VeloTAB additions.
