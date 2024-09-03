package net.maxbel.takeItOutPlugin;

import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

public final class TakeItOutPlugin extends JavaPlugin implements Listener {

    @Override
    public void onEnable() {
        // Plugin startup logic
        this.getServer().getMessenger().registerIncomingPluginChannel(this, "takeitout:getstack", new CommandGetStack());

    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }
}
