# Velocity additions for Enthusia TEMP

Do not replace the live proxy with this directory. Apply the included TEMP backend/group to the existing Velocity installation.

Backend key: `TEMP`

- point it at the actual Bloom Test2 backend allocation
- keep it OUT of the normal `try = ["HUB", "SMP"]` fallback chain
- use the existing network forwarding secret; never commit it
- VeloTAB gets a dedicated plain-text TEMP group so it has no guild/economy/rep/Nexo placeholder dependency
- players access it explicitly with `/server TEMP`

Preferred deployment method:

```powershell
.\apply-temp.ps1 `
  -VelocityTomlPath 'D:\path\to\velocity.toml' `
  -VeloTabGroupsPath 'D:\path\to\plugins\velocitab\groups.yml' `
  -BackendHost '<TEST2_BACKEND_HOST>' `
  -BackendPort <TEST2_BACKEND_PORT>
```

The patcher creates timestamped backups, removes stale `FRONTIER_TEST`/`FRONTIERTEST` aliases, installs exactly one `TEMP` backend and restores the backups automatically if validation fails.
