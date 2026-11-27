package dev.nordfjell.perms;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

final class VelocityIdentityTrust {
    private VelocityIdentityTrust() {}

    static boolean load(Path paperGlobalConfig) throws IOException, InvalidConfigurationException {
        if (!Files.isRegularFile(paperGlobalConfig)) return false;
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.load(paperGlobalConfig.toFile());
        return configuration.getBoolean("proxies.velocity.enabled", false)
            && configuration.getBoolean("proxies.velocity.online-mode", false);
    }
}
