package net.maxbel.takeItOutPlugin;

import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.messaging.PluginMessageListener;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;

public class CommandGetStack implements PluginMessageListener {

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        ByteArrayDataInput buf = ByteStreams.newDataInput(message);
        int slot = buf.readInt();
        int inventory = buf.readInt();
        if (player.getInventory().firstEmpty() != -1) {
            ItemStack shulker = player.getInventory().getContents()[inventory];
            if (shulker == null || shulker.getType() == Material.AIR) {
                return;
            }
            ItemMeta meta = shulker.getItemMeta();
            if (meta instanceof BlockStateMeta bsm && bsm.getBlockState() instanceof ShulkerBox box) {
                ItemStack tmp = box.getInventory().getItem(slot);
                if (tmp == null || tmp.getType() == Material.AIR) {
                    return;
                }
                //player.sendMessage(tmp.toString());
                box.getInventory().setItem(slot, new ItemStack(Material.AIR));
                player.getInventory().setItem(player.getInventory().firstEmpty(), player.getInventory().getItemInMainHand());
                player.getInventory().setItemInMainHand(tmp);
                bsm.setBlockState(box);
                shulker.setItemMeta(meta);
            }
        }
    }
}
