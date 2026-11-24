# NordCommands 3.0.0

NordCommands is one JAR for Paper, Folia, and Velocity. It manages command visibility and execution policy on both platforms and supplies the Paper/Folia permission policy.

Files:

- `config.yml` controls allowed command labels and denial text.
- `permissions.yml` controls `players`, `moderators`, and member assignments.

Administration:

- `/nordcommands reload`
- `/nordcommands health`
- `/nordcommands permissions status` (console only)
- `/nordcommands permissions reload` (console only)

NordCommands 3.0.0 reads only its current files. Invalid reload input is rejected while the previous immutable configuration remains active.

On offline-mode Paper/Folia, moderator rights are activated only for a session trusted by NordAuth. On Velocity, offline-mode connections receive only the `players` policy. OP accounts are rejected by the Paper/Folia permission provider.

Build with Maven 3.9+ and JDK 25. The release artifact is `target/NordCommands-3.0.0.jar`; install the same JAR on Paper/Folia and Velocity.
