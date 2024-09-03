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

        //int slot = buf.readInt();
        ItemStack shulker = player.getInventory().getContents()[inventory];

        if (shulker == null || shulker.getType() == Material.AIR) {
            return;
        }
        //if (player.getInventory().getItemInMainHand() != null
        //        && player.getInventory().getItemInMainHand().getType() != Material.AIR)
        //&& player.getInventory().getItemInMainHand().getItemMeta() instanceof BlockStateMeta bsm
        //        && bsm.getBlockState() instanceof ShulkerBox)
        //{
            // Trying to put a shulker into a shulker, prevent this.
         //   return;
        //}
        ItemMeta meta = shulker.getItemMeta();

        if (meta instanceof BlockStateMeta bsm && bsm.getBlockState() instanceof ShulkerBox box) {
            ItemStack tmp = box.getInventory().getItem(slot);

            if(tmp == null || tmp.getType() == Material.AIR) { return;}

            player.sendMessage(tmp.toString());

            //box.getInventory().setItem(slot, player.getInventory().getItemInMainHand());
            box.getInventory().setItem(slot, new ItemStack(Material.AIR));
            //box.getInventory().remove(tmp); //setItem(slot, player.getInventory().getItemInMainHand());
            //player.getInventory().setItemInMainHand(tmp);
            int emptySlot=player.getInventory().firstEmpty();
            player.getInventory().setItem(emptySlot,tmp);// setItemInMainHand(tmp);
            ItemStack stackInMainHand=player.getInventory().getItemInMainHand();
            player.getInventory().setItemInMainHand(tmp);
            player.getInventory().setItem(emptySlot,stackInMainHand);
            bsm.setBlockState(box);
            shulker.setItemMeta(meta);
        }
    }

}
