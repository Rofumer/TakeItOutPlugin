package net.maxbel.takeItOutPlugin;

import org.bukkit.plugin.java.JavaPlugin;

public final class TakeItOutPlugin extends JavaPlugin {

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

        getServer().getMessenger().registerOutgoingPluginChannel(this, TakeItOutChannelListener.WORLD_CONTAINER_STACK_RESPONSE_CHANNEL);
        getServer().getMessenger().registerOutgoingPluginChannel(this, TakeItOutChannelListener.WORLD_CONTAINER_ITEMS_CHANNEL);
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
    }
}
