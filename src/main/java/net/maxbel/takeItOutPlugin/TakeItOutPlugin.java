package net.maxbel.takeItOutPlugin;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class TakeItOutPlugin extends JavaPlugin implements Listener {

    private TakeItOutChannelListener channelListener;

    @Override
    public void onEnable() {
        channelListener = new TakeItOutChannelListener(this);

        getServer().getMessenger().registerIncomingPluginChannel(
                this,
                TakeItOutChannelListener.GET_STACK_CHANNEL,
                channelListener
        );
        getServer().getMessenger().registerIncomingPluginChannel(
                this,
                TakeItOutChannelListener.GET_WORLD_CONTAINER_STACK_CHANNEL,
                channelListener
        );
        getServer().getMessenger().registerIncomingPluginChannel(
                this,
                TakeItOutChannelListener.GET_WORLD_CONTAINER_ITEMS_CHANNEL,
                channelListener
        );
        getServer().getMessenger().registerIncomingPluginChannel(
                this,
                TakeItOutChannelListener.DUMP_INVENTORY_CHANNEL,
                channelListener
        );
        getServer().getMessenger().registerIncomingPluginChannel(
                this,
                TakeItOutChannelListener.PUBLISH_GROUP_CHANNEL,
                channelListener
        );
        getServer().getMessenger().registerIncomingPluginChannel(
                this,
                TakeItOutChannelListener.UNPUBLISH_GROUP_CHANNEL,
                channelListener
        );

        getServer().getMessenger().registerOutgoingPluginChannel(this, TakeItOutChannelListener.WORLD_CONTAINER_STACK_RESPONSE_CHANNEL);
        getServer().getMessenger().registerOutgoingPluginChannel(this, TakeItOutChannelListener.WORLD_CONTAINER_ITEMS_CHANNEL);
        getServer().getMessenger().registerOutgoingPluginChannel(this, TakeItOutChannelListener.SERVER_CONFIG_SYNC_CHANNEL);
        getServer().getMessenger().registerOutgoingPluginChannel(this, TakeItOutChannelListener.SHARED_GROUPS_LIST_CHANNEL);

        getServer().getPluginManager().registerEvents(this, this);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        getServer().getScheduler().runTaskLater(this, () -> {
            channelListener.sendServerConfigSync(player);
            channelListener.sendSharedGroupsList(player);
        }, 1L);
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
    }
}
