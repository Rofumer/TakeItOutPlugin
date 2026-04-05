package net.maxbel.takeItOutPlugin;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.EnderChest;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.messaging.PluginMessageListener;

public class CommandGetStack implements PluginMessageListener {

    private static final int PLAYER_MAIN_INVENTORY_LIMIT = 36;
    private static final int SHULKER_SIZE = 27;

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (message == null || message.length < 8) {
            return;
        }

        ByteArrayDataInput buf = ByteStreams.newDataInput(message);

        int slotInShulker = buf.readInt();
        int shulkerSlot = buf.readInt();

        // Backward compatibility:
        // old client: int + int
        // new client: int + int + boolean
        boolean singleItemMode = message.length >= 9 && buf.readBoolean();

        handleGetStack(player, slotInShulker, shulkerSlot, singleItemMode);
    }

    private void handleGetStack(Player player, int slotInShulker, int shulkerSlot, boolean singleItemMode) {
        PlayerInventory playerInventory = player.getInventory();

        if (!isValidPlayerInventorySlot(playerInventory, shulkerSlot)) {
            return;
        }

        ItemStack shulkerStack = playerInventory.getItem(shulkerSlot);
        if (!isShulkerBoxItem(shulkerStack)) {
            return;
        }

        BlockStateMeta shulkerMeta = (BlockStateMeta) shulkerStack.getItemMeta();
        if (shulkerMeta == null) {
            return;
        }

        BlockState blockState = shulkerMeta.getBlockState();
        if (!(blockState instanceof ShulkerBox shulkerBox)) {
            return;
        }

        Inventory shulkerInventory = shulkerBox.getInventory();

        if (slotInShulker < 0 || slotInShulker >= SHULKER_SIZE) {
            return;
        }

        ItemStack stackInShulker = shulkerInventory.getItem(slotInShulker);
        if (isEmpty(stackInShulker)) {
            return;
        }

        ItemStack extracted = stackInShulker.clone();
        if (singleItemMode) {
            extracted.setAmount(1);
        }

        ItemStack remainingInShulker = stackInShulker.clone();
        remainingInShulker.setAmount(remainingInShulker.getAmount() - extracted.getAmount());
        if (remainingInShulker.getAmount() <= 0) {
            remainingInShulker = null;
        }

        ItemStack currentMainHand = cloneOrNull(playerInventory.getItemInMainHand());

        // 1. Empty hand
        if (isEmpty(currentMainHand)) {
            shulkerInventory.setItem(slotInShulker, remainingInShulker);
            saveShulker(shulkerStack, shulkerMeta, shulkerBox);

            playerInventory.setItemInMainHand(extracted);
            player.updateInventory();
            return;
        }

        // 2. Free slot in player inventory
        int freeSlot = playerInventory.firstEmpty();
        if (freeSlot != -1) {
            shulkerInventory.setItem(slotInShulker, remainingInShulker);
            saveShulker(shulkerStack, shulkerMeta, shulkerBox);

            playerInventory.setItem(freeSlot, currentMainHand);
            playerInventory.setItemInMainHand(extracted);
            player.updateInventory();
            return;
        }

        // 3. Full inventory + partial extraction:
        // try to insert current hand item into the shulker
        if (remainingInShulker != null) {
            shulkerInventory.setItem(slotInShulker, remainingInShulker);

            ItemStack leftover = insertIntoShulker(shulkerInventory, currentMainHand);
            if (!isEmpty(leftover)) {
                // rollback slot to original stack
                shulkerInventory.setItem(slotInShulker, stackInShulker);
                saveShulker(shulkerStack, shulkerMeta, shulkerBox);
                return;
            }

            saveShulker(shulkerStack, shulkerMeta, shulkerBox);

            playerInventory.setItemInMainHand(extracted);
            player.updateInventory();
            return;
        }

        // 4. Full inventory + full-stack extraction:
        // find replaceable inventory item, move it into shulker, move hand item into that slot
        int searchLimit = Math.min(PLAYER_MAIN_INVENTORY_LIMIT, playerInventory.getSize());
        for (int i = searchLimit - 1; i >= 0; --i) {
            ItemStack item = playerInventory.getItem(i);

            if (!canReplaceInventoryItem(item)) {
                continue;
            }

            shulkerInventory.setItem(slotInShulker, item == null ? null : item.clone());
            saveShulker(shulkerStack, shulkerMeta, shulkerBox);

            playerInventory.setItem(i, currentMainHand);
            playerInventory.setItemInMainHand(extracted);
            player.updateInventory();
            return;
        }
    }

    private ItemStack insertIntoShulker(Inventory shulkerInventory, ItemStack toInsert) {
        if (isEmpty(toInsert)) {
            return null;
        }

        ItemStack remaining = toInsert.clone();

        // First pass: merge into existing stacks
        for (int i = 0; i < SHULKER_SIZE && !isEmpty(remaining); i++) {
            ItemStack existing = shulkerInventory.getItem(i);
            if (isEmpty(existing)) {
                continue;
            }

            if (!canStacksMerge(existing, remaining)) {
                continue;
            }

            int max = existing.getMaxStackSize();
            int canMove = Math.min(max - existing.getAmount(), remaining.getAmount());
            if (canMove <= 0) {
                continue;
            }

            existing.setAmount(existing.getAmount() + canMove);
            shulkerInventory.setItem(i, existing);

            remaining.setAmount(remaining.getAmount() - canMove);
            if (remaining.getAmount() <= 0) {
                remaining = null;
            }
        }

        // Second pass: place into empty slots
        for (int i = 0; i < SHULKER_SIZE && !isEmpty(remaining); i++) {
            ItemStack existing = shulkerInventory.getItem(i);
            if (!isEmpty(existing)) {
                continue;
            }

            int move = Math.min(remaining.getAmount(), remaining.getMaxStackSize());
            ItemStack moved = remaining.clone();
            moved.setAmount(move);
            shulkerInventory.setItem(i, moved);

            remaining.setAmount(remaining.getAmount() - move);
            if (remaining.getAmount() <= 0) {
                remaining = null;
            }
        }

        return remaining;
    }

    private void saveShulker(ItemStack shulkerStack, BlockStateMeta shulkerMeta, ShulkerBox shulkerBox) {
        shulkerMeta.setBlockState(shulkerBox);
        shulkerStack.setItemMeta(shulkerMeta);
    }

    private boolean isValidPlayerInventorySlot(PlayerInventory inventory, int slot) {
        return slot >= 0 && slot < inventory.getSize();
    }

    private boolean isShulkerBoxItem(ItemStack stack) {
        if (isEmpty(stack)) {
            return false;
        }

        ItemMeta meta = stack.getItemMeta();
        if (!(meta instanceof BlockStateMeta bsm)) {
            return false;
        }

        return bsm.getBlockState() instanceof ShulkerBox;
    }

    private boolean canReplaceInventoryItem(ItemStack item) {
        if (isEmpty(item)) {
            return false;
        }

        Material type = item.getType();

        // Match new Fabric logic:
        // exclude hoes, axes, shovels
        if (isHoe(type) || isAxe(type) || isShovel(type)) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof BlockStateMeta bsm)) {
            return false;
        }

        BlockState state = bsm.getBlockState();
        return !(state instanceof ShulkerBox) && !(state instanceof EnderChest);
    }

    private boolean canStacksMerge(ItemStack a, ItemStack b) {
        if (isEmpty(a) || isEmpty(b)) {
            return false;
        }

        return a.isSimilar(b) && a.getAmount() < a.getMaxStackSize();
    }

    private boolean isEmpty(ItemStack stack) {
        return stack == null || stack.getType() == Material.AIR || stack.getAmount() <= 0;
    }

    private ItemStack cloneOrNull(ItemStack stack) {
        return isEmpty(stack) ? null : stack.clone();
    }

    private boolean isHoe(Material material) {
        switch (material) {
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

    private boolean isAxe(Material material) {
        switch (material) {
            case WOODEN_AXE:
            case STONE_AXE:
            case IRON_AXE:
            case GOLDEN_AXE:
            case DIAMOND_AXE:
            case NETHERITE_AXE:
                return true;
            default:
                return false;
        }
    }

    private boolean isShovel(Material material) {
        switch (material) {
            case WOODEN_SHOVEL:
            case STONE_SHOVEL:
            case IRON_SHOVEL:
            case GOLDEN_SHOVEL:
            case DIAMOND_SHOVEL:
            case NETHERITE_SHOVEL:
                return true;
            default:
                return false;
        }
    }
}