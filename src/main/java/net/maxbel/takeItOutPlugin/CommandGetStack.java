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
        ItemStack shulker;
        ItemMeta meta;
        if (player.getInventory().firstEmpty() != -1) {
            shulker = player.getInventory().getContents()[inventory];
            if (shulker == null || shulker.getType() == Material.AIR) {
                return;
            }
            meta = shulker.getItemMeta();
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
        else {
            shulker = player.getInventory().getContents()[inventory];
            if (shulker == null || shulker.getType() == Material.AIR) {
                return;
            }
            meta = shulker.getItemMeta();
            if (meta instanceof BlockStateMeta bsm && bsm.getBlockState() instanceof ShulkerBox box) {
                ItemStack item;
                for (int i = Math.min(36, player.getInventory().getSize()) - 1; i >= 0; --i) {
                    item = player.getInventory().getItem(i);
                    ItemMeta itemMeta = item.getItemMeta();
                    if (!isTool(item) && !(itemMeta instanceof BlockStateMeta ibsm && ibsm.getBlockState() instanceof ShulkerBox ibox)) {
                        ItemStack tmp = box.getInventory().getItem(slot);
                        if (tmp == null || tmp.getType() == Material.AIR) {
                            return;
                        }
                        //player.sendMessage(tmp.toString());
                        box.getInventory().setItem(slot, item);
                        player.getInventory().setItem(i, player.getInventory().getItemInMainHand());
                        player.getInventory().setItemInMainHand(tmp);
                        bsm.setBlockState(box);
                        shulker.setItemMeta(meta);
                    }
                }
            }
        }
    }

    public static boolean isTool(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return false;
        }

        Material material = item.getType();

        // Проверяем типы материалов, относящихся к инструментам
        switch (material) {
            case WOODEN_PICKAXE:
            case STONE_PICKAXE:
            case IRON_PICKAXE:
            case GOLDEN_PICKAXE:
            case DIAMOND_PICKAXE:
            case NETHERITE_PICKAXE:
            case WOODEN_AXE:
            case STONE_AXE:
            case IRON_AXE:
            case GOLDEN_AXE:
            case DIAMOND_AXE:
            case NETHERITE_AXE:
            case WOODEN_SHOVEL:
            case STONE_SHOVEL:
            case IRON_SHOVEL:
            case GOLDEN_SHOVEL:
            case DIAMOND_SHOVEL:
            case NETHERITE_SHOVEL:
            case WOODEN_HOE:
            case STONE_HOE:
            case IRON_HOE:
            case GOLDEN_HOE:
            case DIAMOND_HOE:
            case NETHERITE_HOE:
                return true;
            default:
                return false;
        }
    }

}
