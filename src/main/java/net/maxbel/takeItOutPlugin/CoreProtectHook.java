package net.maxbel.takeItOutPlugin;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.logging.Level;

final class CoreProtectHook {

    private static final int MIN_API_VERSION = 6;

    private final JavaPlugin plugin;
    private final Plugin coreProtectPlugin;
    private final Object api;
    private final Method isEnabledMethod;
    private final Method logContainerTransactionMethod;

    CoreProtectHook(JavaPlugin plugin) {
        this.plugin = plugin;

        Plugin foundPlugin = null;
        Object foundApi = null;
        Method foundIsEnabled = null;
        Method foundLogContainerTransaction = null;

        try {
            Plugin candidate = Bukkit.getServer().getPluginManager().getPlugin("CoreProtect");
            if (candidate != null && candidate.isEnabled()) {
                Method getApiMethod = candidate.getClass().getMethod("getAPI");
                Object candidateApi = getApiMethod.invoke(candidate);
                if (candidateApi != null) {
                    Method isEnabled = candidateApi.getClass().getMethod("isEnabled");
                    Method apiVersion = candidateApi.getClass().getMethod("APIVersion");
                    Method logContainerTransaction = candidateApi.getClass().getMethod(
                            "logContainerTransaction",
                            String.class,
                            Location.class
                    );

                    boolean enabled = Boolean.TRUE.equals(isEnabled.invoke(candidateApi));
                    int version = (Integer) apiVersion.invoke(candidateApi);

                    if (enabled && version >= MIN_API_VERSION) {
                        foundPlugin = candidate;
                        foundApi = candidateApi;
                        foundIsEnabled = isEnabled;
                        foundLogContainerTransaction = logContainerTransaction;
                        plugin.getLogger().info("CoreProtect integration enabled (API v" + version + ")");
                    } else if (!enabled) {
                        plugin.getLogger().info("CoreProtect detected, but its API is disabled in CoreProtect config");
                    } else {
                        plugin.getLogger().warning("CoreProtect API v" + version + " is too old (minimum v" + MIN_API_VERSION + ")");
                    }
                }
            }
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.WARNING, "Failed to initialize CoreProtect integration", throwable);
        }

        this.coreProtectPlugin = foundPlugin;
        this.api = foundApi;
        this.isEnabledMethod = foundIsEnabled;
        this.logContainerTransactionMethod = foundLogContainerTransaction;
    }

    boolean isAvailable() {
        return api != null && coreProtectPlugin != null && coreProtectPlugin.isEnabled();
    }

    void logContainerTransaction(Player player, Location location) {
        if (!isAvailable() || player == null || location == null) {
            return;
        }

        try {
            if (Boolean.TRUE.equals(isEnabledMethod.invoke(api))) {
                logContainerTransactionMethod.invoke(api, player.getName(), location);
            }
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.WARNING, "Failed to log container transaction to CoreProtect", throwable);
        }
    }
}
