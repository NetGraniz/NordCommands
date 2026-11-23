package dev.nordfjell.perms;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.*;
import org.bukkit.plugin.Plugin;

final class NordPermissible extends PermissibleBase {
    private final Player player;
    private final PermissionEngine engine;
    private final PermissibleBase delegate;
    private volatile Map<String, PermissionAttachmentInfo> attached = Map.of();

    NordPermissible(Player player, PermissionEngine engine, PermissibleBase delegate) {
        super(player); this.player = player; this.engine = engine; this.delegate = delegate; refresh();
    }
    private void refresh() {
        Map<String, PermissionAttachmentInfo> copy = new HashMap<>();
        delegate.getEffectivePermissions().forEach(info -> copy.put(info.getPermission(), info));
        attached = Map.copyOf(copy);
    }
    @Override public boolean isOp() { return false; }
    @Override public void setOp(boolean value) { throw new UnsupportedOperationException("NordCommands does not manage OP"); }
    @Override public boolean isPermissionSet(String name) {
        String node = Policy.normalize(name); Policy snapshot = engine.policy.get();
        return snapshot != null && (engine.permissions(snapshot, player).containsKey(node) || attached.containsKey(node));
    }
    @Override public boolean isPermissionSet(Permission permission) { return isPermissionSet(permission.getName()); }
    @Override public boolean hasPermission(String name) {
        String node = Policy.normalize(name); Policy snapshot = engine.policy.get();
        if (snapshot == null) return false;
        Boolean configured = engine.permissions(snapshot, player).get(node);
        if (configured != null) return configured;
        PermissionAttachmentInfo info = attached.get(node);
        if (info != null) return info.getValue();
        Permission registered = Bukkit.getPluginManager().getPermission(node);
        return registered == null ? Permission.DEFAULT_PERMISSION.getValue(false) : registered.getDefault().getValue(false);
    }
    @Override public boolean hasPermission(Permission permission) {
        Policy snapshot = engine.policy.get(); if (snapshot == null) return false;
        String node = Policy.normalize(permission.getName()); Boolean configured = engine.permissions(snapshot, player).get(node);
        if (configured != null) return configured;
        PermissionAttachmentInfo info = attached.get(node);
        return info != null ? info.getValue() : permission.getDefault().getValue(false);
    }
    @Override public synchronized void recalculatePermissions() { if (delegate != null) { delegate.recalculatePermissions(); refresh(); } }
    @Override public synchronized void clearPermissions() { if (delegate != null) { delegate.clearPermissions(); attached = Map.of(); } }
    @Override public synchronized PermissionAttachment addAttachment(Plugin plugin) { PermissionAttachment result = delegate.addAttachment(plugin); refresh(); return result; }
    @Override public synchronized PermissionAttachment addAttachment(Plugin plugin, String name, boolean value) { PermissionAttachment a = addAttachment(plugin); a.setPermission(name, value); return a; }
    @Override public PermissionAttachment addAttachment(Plugin plugin, int ticks) {
        if (ticks <= 0) throw new IllegalArgumentException("Attachment duration must be positive");
        PermissionAttachment attachment = addAttachment(plugin);
        var task = player.getScheduler().runDelayed(plugin, ignored -> attachment.remove(), null, ticks);
        if (task == null) { attachment.remove(); return null; }
        return attachment;
    }
    @Override public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value, int ticks) { PermissionAttachment a = addAttachment(plugin, ticks); if (a != null) a.setPermission(name, value); return a; }
    @Override public synchronized void removeAttachment(PermissionAttachment attachment) { delegate.removeAttachment(attachment); refresh(); }
    @Override public Set<PermissionAttachmentInfo> getEffectivePermissions() {
        Policy snapshot = engine.policy.get(); if (snapshot == null) return Set.of();
        Map<String, PermissionAttachmentInfo> copy = new HashMap<>(attached);
        engine.permissions(snapshot, player).forEach((node, value) -> copy.put(node, new PermissionAttachmentInfo(player, node, null, value)));
        return new HashSet<>(copy.values());
    }
}
