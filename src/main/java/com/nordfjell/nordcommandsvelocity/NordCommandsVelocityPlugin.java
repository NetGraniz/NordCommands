package com.nordfjell.nordcommandsvelocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.command.PlayerAvailableCommandsEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.permission.PermissionsSetupEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.permission.*;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.nordfjell.nordcommandspaper.CommandSettings;
import dev.nordfjell.perms.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

@Plugin(id="nordcommands", name="NordCommands", version="3.0.1",
        description="Universal command and permission policy for Paper, Folia, and Velocity", authors={"Nord Fjell"})
public final class NordCommandsVelocityPlugin {
    private final ProxyServer proxy; private final Logger logger; private final Path dataDirectory;
    private final NoticeGate notices = new NoticeGate(); private final PolicyLoader policyLoader = new PolicyLoader();
    private final AtomicBoolean reloading = new AtomicBoolean();
    private volatile CommandSettings settings; private volatile Policy policy;
    private Path configPath, policyPath;

    @Inject public NordCommandsVelocityPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy; this.logger = logger; this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        configPath = dataDirectory.resolve("config.yml"); policyPath = dataDirectory.resolve("permissions.yml");
        try {
            Files.createDirectories(dataDirectory); copyDefault("config.yml", configPath); copyDefault("permissions.yml", policyPath);
            settings = CommandSettings.load(configPath); policy = policyLoader.load(policyPath);
        } catch (Exception error) {
            logger.error("NordCommands configuration unavailable; proxy commands and configured permissions fail closed: {}", error.getClass().getSimpleName());
        }
        var command = proxy.getCommandManager().metaBuilder("nordcommands").plugin(this).build();
        proxy.getCommandManager().register(command, new ManagementCommand());
        logger.info("NordCommands 3.0.1 enabled: command labels={}, permission policy={}", settings == null ? 0 : settings.allowed().size(), policy == null ? "blocked" : "ready");
    }

    private void copyDefault(String name, Path target) throws IOException {
        if (Files.exists(target)) return;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(name)) {
            if (input == null) throw new IOException("Missing bundled " + name);
            Files.copy(input, target);
        }
    }

    @Subscribe(priority = Short.MIN_VALUE)
    public void onAvailableCommands(PlayerAvailableCommandsEvent event) {
        if (canBypass(event.getPlayer())) return;
        event.getRootNode().getChildren().removeIf(node -> proxy.getCommandManager().hasCommand(node.getName()) && !allowed(node.getName()));
    }

    @Subscribe(priority = Short.MAX_VALUE) public void onCommand(CommandExecuteEvent event) { filter(event, false); }
    @Subscribe(priority = Short.MIN_VALUE) public void onFinalCommand(CommandExecuteEvent event) { filter(event, true); }

    private void filter(CommandExecuteEvent event, boolean notify) {
        if (!(event.getCommandSource() instanceof Player player) || canBypass(player)) return;
        String originalText = event.getCommand(); String original = CommandInput.label(originalText);
        String effectiveText = event.getResult().getCommand().orElse(originalText);
        String effective = effectiveText.equals(originalText) ? original : CommandInput.label(effectiveText);
        boolean invalid = original == null || effective == null;
        boolean blockedProxyCommand = !invalid && ((proxy.getCommandManager().hasCommand(original) && !allowed(original))
                || (proxy.getCommandManager().hasCommand(effective) && !allowed(effective)));
        if (invalid || blockedProxyCommand) {
            event.setResult(CommandExecuteEvent.CommandResult.denied());
            if (notify && player.isActive() && notices.admit(player, System.nanoTime()))
                player.sendMessage(Component.text(settings == null ? "Command policy is unavailable." : settings.deniedMessage()));
        }
    }

    private boolean allowed(String label) { CommandSettings current = settings; return current != null && current.allows(label); }
    private boolean canBypass(Player player) { return player.hasPermission("nordcommands.bypass"); }

    @Subscribe(priority = Short.MIN_VALUE)
    public void onPermissions(PermissionsSetupEvent event) {
        if (!(event.getSubject() instanceof Player player)) return;
        PermissionFunction fallback = event.createFunction(player);
        event.setProvider(subject -> node -> {
            Policy snapshot = policy;
            if (snapshot == null) return node.startsWith("nord") ? Tristate.FALSE : fallback.getPermissionValue(node);
            boolean trustedModerator = player.isOnlineMode() && snapshot.members().contains(player.getUniqueId());
            Boolean configured = snapshot.permissions(player.getUniqueId(), trustedModerator).get(Policy.normalize(node));
            return configured == null ? fallback.getPermissionValue(node) : Tristate.fromBoolean(configured);
        });
    }

    @Subscribe public void onDisconnect(DisconnectEvent event) { notices.remove(event.getPlayer()); }
    @Subscribe public void onShutdown(ProxyShutdownEvent event) { notices.close(); }

    private final class ManagementCommand implements SimpleCommand {
        @Override public void execute(Invocation invocation) {
            String[] args = invocation.arguments();
            if (invocation.source() instanceof Player && !invocation.source().hasPermission("nordcommands.admin")) {
                invocation.source().sendMessage(Component.text("You do not have permission.")); return;
            }
            if (args.length == 1 && args[0].equalsIgnoreCase("health")) {
                invocation.source().sendMessage(Component.text("NordCommands: command policy=" + (settings == null ? "blocked" : "ready")
                        + ", permission policy=" + (policy == null ? "blocked" : "ready"))); return;
            }
            if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) {
                invocation.source().sendMessage(Component.text("Usage: /nordcommands <reload|health>")); return;
            }
            if (!reloading.compareAndSet(false, true)) { invocation.source().sendMessage(Component.text("NordCommands reload already pending.")); return; }
            invocation.source().sendMessage(Component.text("NordCommands reload queued."));
            proxy.getScheduler().buildTask(NordCommandsVelocityPlugin.this, () -> {
                try {
                    CommandSettings candidateSettings = CommandSettings.load(configPath);
                    Policy candidatePolicy = policyLoader.load(policyPath);
                    settings = candidateSettings; policy = candidatePolicy;
                    invocation.source().sendMessage(Component.text("NordCommands configuration reloaded."));
                    logger.info("NordCommands reloaded: labels={}, moderator UUIDs={}", candidateSettings.allowed().size(), candidatePolicy.members().size());
                } catch (Exception error) {
                    invocation.source().sendMessage(Component.text("NordCommands reload rejected; previous configuration retained."));
                    logger.warn("NordCommands reload rejected; previous configuration retained: {}", error.getClass().getSimpleName());
                } finally { reloading.set(false); }
            }).schedule();
        }

        @Override public List<String> suggest(Invocation invocation) {
            return invocation.arguments().length <= 1 ? List.of("reload", "health") : List.of();
        }
    }
}
