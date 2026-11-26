package dev.nordfjell.perms;

import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.connection.PlayerLoginConnection;
import io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.java.JavaPlugin;

public final class PaperPermissionsFeature implements Listener {
    private final JavaPlugin host;
    private final PermissionEngine engine = new PermissionEngine();
    private final Injector injector = new Injector();
    private final PolicyLoader loader = new PolicyLoader();
    private final AtomicBoolean loading = new AtomicBoolean(), closing = new AtomicBoolean();
    private volatile Policy source;
    private volatile Map<String, Permission> registry = Map.of();
    private ExecutorService io;
    private volatile boolean started;
    private Path policyPath;

    public PaperPermissionsFeature(JavaPlugin host) { this.host = host; }

    public void enable() {
        io = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("NordCommands-permissions").daemon(true).factory());
        Bukkit.getPluginManager().registerEvents(this, host);
        policyPath = host.getDataFolder().toPath().resolve("permissions.yml");
        if (!Files.exists(policyPath)) host.saveResource("permissions.yml", false);
        try {
            injector.validatePlatform(); updateRegistry();
            engine.auth = new AuthGate(Bukkit.getPluginManager().getPlugin("NordAuth"), Bukkit.getOnlineMode());
            source = loader.load(policyPath);
            engine.policy.set(PermissionEngine.expand(source, registry)); started = true;
            if (!engine.auth.ready()) host.getLogger().warning("No trusted identity provider: moderator permissions remain denied");
            host.getLogger().info("Integrated permissions ready; " + source.members().size() + " moderator UUIDs.");
        } catch (Exception error) {
            host.getLogger().severe("Permission provider startup refused: " + error.getClass().getSimpleName() + ". Check permissions.yml and provider compatibility.");
            engine.policy.set(null);
        }
        if (!Bukkit.getOnlinePlayers().isEmpty()) fatal("Live permission-provider loading is unsupported; use a full restart");
    }

    public void disable() {
        closing.set(true); engine.policy.set(null); if (io != null) io.shutdownNow();
        if (started && !Bukkit.isStopping()) Bukkit.shutdown();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void login(PlayerConnectionValidateLoginEvent event) {
        var connection = event.getConnection();
        var profile = connection instanceof PlayerLoginConnection login ? login.getUnsafeProfile()
            : connection instanceof PlayerConfigurationConnection configuration ? configuration.getProfile() : null;
        var id = profile == null ? null : profile.getId();
        boolean operator = id != null && Bukkit.getOperators().stream().anyMatch(op -> id.equals(op.getUniqueId()));
        if (engine.policy.get() == null || operator)
            event.kickMessage(Component.text("Permission provider unavailable or OP account unsupported. Contact the administrator."));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void join(PlayerJoinEvent event) {
        if (event.getPlayer().isOp() || engine.policy.get() == null) {
            event.getPlayer().kick(Component.text("Permission provider unavailable or OP account unsupported.")); return;
        }
        try { injector.install(event.getPlayer(), engine); injector.verify(event.getPlayer()); event.getPlayer().updateCommands(); }
        catch (Exception error) { fatal("Cannot install or verify permissible on join"); }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void protectAccounts(PlayerCommandPreprocessEvent event) {
        String[] arguments = event.getMessage().substring(1).trim().split("\\s+", 4);
        String label = arguments[0].toLowerCase(Locale.ROOT); if (label.contains(":")) label = label.substring(label.indexOf(':') + 1);
        Policy snapshot = engine.policy.get();
        if (label.equals("register") && snapshot != null && snapshot.members().contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true); event.getPlayer().sendMessage("Staff accounts must already be registered before their UUID is added to permissions.yml."); return;
        }
        if (!label.equals("resetpassword") || arguments.length < 2) return;
        Player target = Bukkit.getPlayerExact(arguments[1]);
        if (snapshot == null || target == null || target.getUniqueId().equals(event.getPlayer().getUniqueId()) || snapshot.members().contains(target.getUniqueId()) || target.isOp()) {
            event.setCancelled(true); event.getPlayer().sendMessage("Staff/self/offline password recovery is console-only.");
        }
    }

    @EventHandler public void enabled(PluginEnableEvent event) {
        if (closing.get()) return;
        if (event.getPlugin().getName().equals("NordAuth")) engine.auth = new AuthGate(event.getPlugin(), Bukkit.getOnlineMode());
        try { updateRegistry(); if (source != null) engine.policy.set(PermissionEngine.expand(source, registry)); }
        catch (Exception error) { fatal("Cannot expand registered permission children"); }
    }

    private void updateRegistry() {
        Map<String, Permission> copy = new HashMap<>();
        Bukkit.getPluginManager().getPermissions().forEach(permission -> copy.put(Policy.normalize(permission.getName()), permission));
        registry = Map.copyOf(copy);
    }
    private void fatal(String message) {
        closing.set(true); engine.policy.set(null);
        host.getLogger().severe(message + "; stopping the server rather than granting fallback rights"); Bukkit.shutdown();
    }

    public boolean command(CommandSender sender, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) { sender.sendMessage("Permission management is available only from the server console."); return true; }
        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            Policy active = engine.policy.get();
            sender.sendMessage("NordCommands permissions: " + (active == null ? "UNAVAILABLE" : "ready; moderator UUIDs=" + active.members().size())
                + "; trusted identity=" + (engine.auth != null && engine.auth.ready())); return true;
        }
        if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) return false;
        if (closing.get() || Bukkit.isStopping()) { sender.sendMessage("Refused: server is stopping."); return true; }
        if (!loading.compareAndSet(false, true)) { sender.sendMessage("Permission reload already in progress."); return true; }
        io.execute(() -> {
            try {
                Policy candidate = loader.load(policyPath);
                Bukkit.getGlobalRegionScheduler().execute(host, () -> {
                    try {
                        if (closing.get() || !host.isEnabled() || Bukkit.isStopping()) return;
                        updateRegistry(); Policy expanded = PermissionEngine.expand(candidate, registry);
                        source = candidate; engine.policy.set(expanded); started = true;
                        Bukkit.getOnlinePlayers().forEach(player -> player.getScheduler().run(host, ignored -> player.updateCommands(), null));
                        host.getLogger().info("Permissions reloaded; moderator UUIDs=" + candidate.members().size());
                    } catch (Exception error) { host.getLogger().warning("Permission reload rejected; previous policy retained: " + error.getClass().getSimpleName()); }
                    finally { loading.set(false); }
                });
            } catch (Exception error) { host.getLogger().warning("Permission reload rejected; previous policy retained: " + error.getClass().getSimpleName()); loading.set(false); }
        });
        return true;
    }

    public List<String> tab(CommandSender sender, String[] args) {
        return sender instanceof ConsoleCommandSender && args.length == 1 ? List.of("reload", "status") : List.of();
    }
}
