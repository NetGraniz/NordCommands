# Paper and Folia compatibility

NordCommands 3.0.2 supports Paper, Folia, and Velocity on Java 25. Player command checks and command-tree refreshes run on the owning entity scheduler. Reload completion uses the global-region scheduler, and file parsing runs outside region callbacks.

Run `mvn clean verify` before deployment. Preserve the current `plugins/NordCommands` directory and replace only the plugin JAR while the server is stopped.
