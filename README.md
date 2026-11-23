# NordCommands 2.0.0

One universal JAR for Paper, Folia, and Velocity. It combines command visibility/execution policy, the former NordCommandsVelocity module, and the former NordPerms permission policy.

## Files

- `config.yml` — allowed command labels and denial text.
- `permissions.yml` — immutable `players`/`moderators` permission policy.

The Paper/Folia plugin imports `plugins/NordPerms/config.yml` into `plugins/NordCommands/permissions.yml` on first start when the new file does not exist. Installed data is never published with the repository.

## Reload

- `/nordcommands reload` validates and atomically reloads both Velocity files, or the command policy on Paper/Folia.
- `/nordcommands health` reports active policy state.
- `nordperms reload` and `nordperms status` remain console-only compatibility commands on Paper/Folia.

Malformed, oversized, duplicate-key, wildcard, or unknown policy input is rejected. A failed reload retains the previous immutable snapshot.

## Identity safety

On offline-mode Paper/Folia, moderator UUIDs receive elevated rights only after NordAuth confirms the session. On Velocity, there is no trusted NordAuth session bridge; therefore offline-mode proxy connections receive only the `players` policy. This prevents a spoofed UUID from receiving moderator proxy permissions. Online-mode Velocity connections may use UUID assignments.

The provider rejects OP accounts and refuses to coexist with LuckPerms on Paper/Folia. Do not hot-load, hot-disable, or use server `/reload`.

## Build

Use Maven 3.9+ and JDK 25:

```text
mvn -B -ntp clean verify
```

The release artifact is `target/NordCommands-2.0.0.jar`. Install the same file on Paper/Folia and Velocity.
