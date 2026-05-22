package net.maxbel.takeItOutPlugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

public final class TakeItOutChannelListener implements PluginMessageListener {

    public static final String GET_STACK_CHANNEL = "takeitout:getstack";
    public static final String GET_WORLD_CONTAINER_STACK_CHANNEL = "takeitout:get_world_container_stack";
    public static final String GET_WORLD_CONTAINER_ITEMS_CHANNEL = "takeitout:get_world_container_items";
    public static final String WORLD_CONTAINER_STACK_RESPONSE_CHANNEL = "takeitout:world_container_stack_response";
    public static final String WORLD_CONTAINER_ITEMS_CHANNEL = "takeitout:world_container_items";

    private static final int PLAYER_MAIN_INVENTORY_LIMIT = 36;
    private static final int SHULKER_SIZE = 27;
    private static final int MAX_SOURCE_POSITIONS = 64;
    private static final int DEFAULT_LINKED_CONTAINER_SCAN_LIMIT = 64;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String LINKED_CONTAINER_EXCHANGE_MODE_KEY = "linked_container_exchange_mode";
    private static final String LINKED_CONTAINER_SCAN_LIMIT_KEY = "linked_container_scan_limit";
    private static final String ALLOWED_EXCHANGE_DIMENSIONS_KEY = "allowed_exchange_dimensions";

    private final JavaPlugin plugin;
    private final MinecraftItemStackCodec itemStackCodec;
    private final Path serverConfigPath;
    private final Set<String> allowedExchangeDimensions = new HashSet<>();
    private LinkedContainerExchangeMode linkedContainerExchangeMode = LinkedContainerExchangeMode.CROSS_DIMENSION;
    private int linkedContainerScanLimit = DEFAULT_LINKED_CONTAINER_SCAN_LIMIT;

    public TakeItOutChannelListener(JavaPlugin plugin) {
        this.plugin = plugin;
        this.itemStackCodec = new MinecraftItemStackCodec();
        this.serverConfigPath = plugin.getDataFolder().toPath().resolve("takeitout-server.json");
        loadServerConfig();
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (message == null) {
            return;
        }

        try {
            switch (channel) {
                case GET_STACK_CHANNEL -> handleGetShulkerStack(player, message);
                case GET_WORLD_CONTAINER_STACK_CHANNEL -> handleGetWorldContainerStack(player, message);
                case GET_WORLD_CONTAINER_ITEMS_CHANNEL -> handleGetWorldContainerItems(player, message);
                default -> {
                }
            }
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to handle plugin message on channel " + channel, exception);
        }
    }

    private void handleGetShulkerStack(Player player, byte[] message) {
        if (message.length < 8) {
            return;
        }

        PacketReader reader = new PacketReader(message);
        int slotInShulker = reader.readInt();
        int shulkerSlot = reader.readInt();
        boolean singleItemMode = reader.hasRemaining() && reader.readBoolean();

        handleGetShulkerStack(player, slotInShulker, shulkerSlot, singleItemMode);
    }

    private void handleGetShulkerStack(Player player, int slotInShulker, int shulkerSlot, boolean singleItemMode) {
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

        if (isEmpty(currentMainHand)) {
            shulkerInventory.setItem(slotInShulker, remainingInShulker);
            saveShulker(shulkerStack, shulkerMeta, shulkerBox);
            playerInventory.setItemInMainHand(extracted);
            syncPlayerInventory(player);
            return;
        }

        int freeSlot = playerInventory.firstEmpty();
        if (freeSlot != -1) {
            shulkerInventory.setItem(slotInShulker, remainingInShulker);
            saveShulker(shulkerStack, shulkerMeta, shulkerBox);
            playerInventory.setItem(freeSlot, currentMainHand);
            playerInventory.setItemInMainHand(extracted);
            syncPlayerInventory(player);
            return;
        }

        if (remainingInShulker != null) {
            shulkerInventory.setItem(slotInShulker, remainingInShulker);
            ItemStack leftover = insertIntoInventory(shulkerInventory, currentMainHand);
            if (isEmpty(leftover)) {
                saveShulker(shulkerStack, shulkerMeta, shulkerBox);
                playerInventory.setItemInMainHand(extracted);
                syncPlayerInventory(player);
                return;
            }

            shulkerInventory.setItem(slotInShulker, stackInShulker);
            saveShulker(shulkerStack, shulkerMeta, shulkerBox);
        }

        if (remainingInShulker == null) {
            int searchLimit = Math.min(PLAYER_MAIN_INVENTORY_LIMIT, playerInventory.getSize());
            for (int i = searchLimit - 1; i >= 0; --i) {
                ItemStack item = playerInventory.getItem(i);
                if (!canReplaceInventoryItem(item)) {
                    continue;
                }

                shulkerInventory.setItem(slotInShulker, item.clone());
                saveShulker(shulkerStack, shulkerMeta, shulkerBox);
                playerInventory.setItem(i, currentMainHand);
                playerInventory.setItemInMainHand(extracted);
                syncPlayerInventory(player);
                return;
            }
        }
    }

    private void handleGetWorldContainerStack(Player player, byte[] message) {
        PacketReader reader = new PacketReader(message);
        List<WorldContainerSource> sources = reader.readWorldContainerSources(maxSourcePositionsToRead());
        ItemStack requested = itemStackCodec.decode(reader);
        boolean singleItemMode = reader.readBoolean();

        if (requested == null || isEmpty(requested) || sources == null) {
            return;
        }

        int checked = 0;
        int invalidSourceCount = 0;
        int emptySourceCount = 0;
        int failedExtractCount = 0;
        int scanLimit = linkedContainerScanLimit;

        for (WorldContainerSource source : sources) {
            if (checked >= scanLimit) {
                break;
            }
            checked++;

            Inventory inventory = getWorldContainerInventory(player, source);
            if (inventory == null) {
                invalidSourceCount++;
                continue;
            }

            int slot = getSlotWithStack(inventory, requested);
            if (slot != -1 && extractFromWorldContainer(player, inventory, slot, requested, singleItemMode)) {
                sendWorldContainerStackResponse(player, copySingle(requested), true);
                return;
            }

            if (slot == -1) {
                emptySourceCount++;
            } else {
                failedExtractCount++;
            }
        }

        sendWorldContainerStackResponse(player, copySingle(requested), false);
        plugin.getLogger().warning(
                "GetWorldContainerStack miss: player=" + player.getName()
                        + ", requested=" + requested
                        + ", sources=" + Math.min(sources.size(), scanLimit)
                        + ", invalidSources=" + invalidSourceCount
                        + ", noMatchingStack=" + emptySourceCount
                        + ", failedExtract=" + failedExtractCount
        );
    }

    private void handleGetWorldContainerItems(Player player, byte[] message) {
        PacketReader reader = new PacketReader(message);
        List<WorldContainerSource> sources = reader.readWorldContainerSources(maxSourcePositionsToRead());

        List<WorldContainerItemCount> items = new ArrayList<>();
        List<WorldContainerContents> containers = new ArrayList<>();

        if (sources == null) {
            sendWorldContainerItems(player, items, containers);
            return;
        }

        int checked = 0;
        int scanLimit = linkedContainerScanLimit;
        for (WorldContainerSource source : sources) {
            if (checked >= scanLimit) {
                break;
            }
            checked++;

            Inventory inventory = getWorldContainerInventory(player, source);
            List<WorldContainerItemCount> containerItems = new ArrayList<>();

            if (inventory != null) {
                for (int slot = 0; slot < inventory.getSize(); slot++) {
                    ItemStack stack = inventory.getItem(slot);
                    if (isEmpty(stack)) {
                        continue;
                    }

                    addItemCount(items, stack);
                    addItemCount(containerItems, stack);
                }
            }

            containers.add(new WorldContainerContents(source, containerItems));
        }

        sendWorldContainerItems(player, items, containers);
    }

    private boolean extractFromWorldContainer(
            Player player,
            Inventory inventory,
            int slot,
            ItemStack requested,
            boolean singleItemMode
    ) {
        if (slot < 0 || slot >= inventory.getSize()) {
            return false;
        }

        ItemStack stackInContainer = inventory.getItem(slot);
        if (isEmpty(stackInContainer) || !canStacksMerge(stackInContainer, requested)) {
            return false;
        }

        ItemStack extracted = stackInContainer.clone();
        if (singleItemMode) {
            extracted.setAmount(1);
        }

        ItemStack remainingInContainer = stackInContainer.clone();
        remainingInContainer.setAmount(remainingInContainer.getAmount() - extracted.getAmount());
        if (remainingInContainer.getAmount() <= 0) {
            remainingInContainer = null;
        }

        PlayerInventory playerInventory = player.getInventory();
        ItemStack currentMainHand = cloneOrNull(playerInventory.getItemInMainHand());

        if (isEmpty(currentMainHand)) {
            inventory.setItem(slot, remainingInContainer);
            playerInventory.setItemInMainHand(extracted);
            syncPlayerInventory(player);
            return true;
        }

        int freeSlot = playerInventory.firstEmpty();
        if (freeSlot != -1) {
            inventory.setItem(slot, remainingInContainer);
            playerInventory.setItem(freeSlot, currentMainHand);
            playerInventory.setItemInMainHand(extracted);
            syncPlayerInventory(player);
            return true;
        }

        inventory.setItem(slot, remainingInContainer);
        if (canInsertIntoInventory(inventory, currentMainHand)) {
            ItemStack leftover = insertIntoInventory(inventory, currentMainHand);
            if (isEmpty(leftover)) {
                playerInventory.setItemInMainHand(extracted);
                syncPlayerInventory(player);
                return true;
            }
        }

        inventory.setItem(slot, stackInContainer);

        if (remainingInContainer == null) {
            int searchLimit = Math.min(PLAYER_MAIN_INVENTORY_LIMIT, playerInventory.getSize());
            for (int i = searchLimit - 1; i >= 0; --i) {
                ItemStack item = playerInventory.getItem(i);
                if (!canReplaceInventoryItem(item)) {
                    continue;
                }

                inventory.setItem(slot, item.clone());
                playerInventory.setItem(i, currentMainHand);
                playerInventory.setItemInMainHand(extracted);
                syncPlayerInventory(player);
                return true;
            }
        }

        plugin.getLogger().fine(
                "GetWorldContainerStack aborted: player=" + player.getName()
                        + ", slot=" + slot
                        + ", currentHand=" + currentMainHand
                        + ", extracted=" + extracted
        );
        return false;
    }

    private Inventory getWorldContainerInventory(Player player, WorldContainerSource source) {
        World world = getSourceWorld(player, source);
        if (world == null) {
            return null;
        }

        PackedBlockPos pos = PackedBlockPos.unpack(source.position());

        if (pos.y < world.getMinHeight() || pos.y >= world.getMaxHeight()) {
            return null;
        }

        int chunkX = pos.x >> 4;
        int chunkZ = pos.z >> 4;
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return null;
        }

        BlockState blockState = world.getBlockAt(pos.x, pos.y, pos.z).getState();
        if (blockState instanceof Chest chest) {
            return chest.getInventory();
        }
        if (blockState instanceof ShulkerBox shulkerBox) {
            return shulkerBox.getInventory();
        }
        if (blockState instanceof Barrel barrel) {
            return barrel.getInventory();
        }
        return null;
    }

    private World getSourceWorld(Player player, WorldContainerSource source) {
        if (source == null || source.dimension() == null || source.dimension().isBlank()) {
            return null;
        }

        String playerDimension = player.getWorld().getKey().toString();
        if (linkedContainerExchangeMode == LinkedContainerExchangeMode.DISABLED) {
            plugin.getLogger().warning(
                    "World container source blocked by server config: player=" + player.getName()
                            + ", reason=disabled"
                            + ", playerDimension=" + playerDimension
                            + ", sourceDimension=" + source.dimension()
                            + ", pos=" + PackedBlockPos.unpack(source.position())
            );
            return null;
        }

        if (linkedContainerExchangeMode == LinkedContainerExchangeMode.SAME_DIMENSION
                && !playerDimension.equals(source.dimension())) {
            plugin.getLogger().warning(
                    "World container source blocked by server config: player=" + player.getName()
                            + ", reason=same_dimension_only"
                            + ", playerDimension=" + playerDimension
                            + ", sourceDimension=" + source.dimension()
                            + ", pos=" + PackedBlockPos.unpack(source.position())
            );
            return null;
        }

        if (!isDimensionAllowedForExchange(playerDimension) || !isDimensionAllowedForExchange(source.dimension())) {
            plugin.getLogger().warning(
                    "World container source blocked by server config: player=" + player.getName()
                            + ", playerDimension=" + playerDimension
                            + ", sourceDimension=" + source.dimension()
                            + ", pos=" + PackedBlockPos.unpack(source.position())
            );
            return null;
        }

        if (!isValidDimensionId(source.dimension())) {
            return null;
        }

        for (World world : Bukkit.getWorlds()) {
            if (world.getKey().toString().equals(source.dimension())) {
                return world;
            }
        }
        return null;
    }

    private boolean isDimensionAllowedForExchange(String dimension) {
        return allowedExchangeDimensions.isEmpty() || allowedExchangeDimensions.contains(dimension);
    }

    private boolean isValidDimensionId(String dimension) {
        try {
            return NamespacedKey.fromString(dimension) != null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void sendWorldContainerStackResponse(Player player, ItemStack stack, boolean success) {
        PacketWriter writer = new PacketWriter();
        itemStackCodec.encode(writer, stack);
        writer.writeBoolean(success);
        player.sendPluginMessage(plugin, WORLD_CONTAINER_STACK_RESPONSE_CHANNEL, writer.toByteArray());
    }

    private void sendWorldContainerItems(
            Player player,
            List<WorldContainerItemCount> items,
            List<WorldContainerContents> containers
    ) {
        PacketWriter writer = new PacketWriter();
        writer.writeVarInt(items.size());
        for (WorldContainerItemCount item : items) {
            itemStackCodec.encode(writer, item.stack());
            writer.writeInt(item.count());
        }

        writer.writeVarInt(containers.size());
        for (WorldContainerContents container : containers) {
            writer.writeWorldContainerSource(container.source());
            writer.writeVarInt(container.items().size());
            for (WorldContainerItemCount item : container.items()) {
                itemStackCodec.encode(writer, item.stack());
                writer.writeInt(item.count());
            }
        }

        player.sendPluginMessage(plugin, WORLD_CONTAINER_ITEMS_CHANNEL, writer.toByteArray());
    }

    private void addItemCount(List<WorldContainerItemCount> items, ItemStack stack) {
        ItemStack key = copySingle(stack);
        for (int i = 0; i < items.size(); i++) {
            WorldContainerItemCount existing = items.get(i);
            if (existing.stack().isSimilar(key)) {
                items.set(i, new WorldContainerItemCount(existing.stack(), existing.count() + stack.getAmount()));
                return;
            }
        }

        items.add(new WorldContainerItemCount(key, stack.getAmount()));
    }

    private int maxSourcePositionsToRead() {
        return Math.max(MAX_SOURCE_POSITIONS, linkedContainerScanLimit);
    }

    private int getSlotWithStack(Inventory inventory, ItemStack reference) {
        for (int i = 0; i < inventory.getSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!isEmpty(stack) && stack.isSimilar(reference)) {
                return i;
            }
        }
        return -1;
    }

    private boolean canInsertIntoInventory(Inventory inventory, ItemStack toInsert) {
        if (isEmpty(toInsert)) {
            return true;
        }

        int remaining = toInsert.getAmount();

        for (int i = 0; i < inventory.getSize() && remaining > 0; i++) {
            ItemStack existing = inventory.getItem(i);
            if (isEmpty(existing) || !canStacksMerge(existing, toInsert)) {
                continue;
            }

            int max = Math.min(existing.getMaxStackSize(), inventory.getMaxStackSize());
            remaining -= Math.max(0, max - existing.getAmount());
        }

        for (int i = 0; i < inventory.getSize() && remaining > 0; i++) {
            ItemStack existing = inventory.getItem(i);
            if (!isEmpty(existing)) {
                continue;
            }

            remaining -= Math.min(toInsert.getMaxStackSize(), inventory.getMaxStackSize());
        }

        return remaining <= 0;
    }

    private ItemStack insertIntoInventory(Inventory inventory, ItemStack toInsert) {
        if (isEmpty(toInsert)) {
            return null;
        }

        ItemStack remaining = toInsert.clone();

        for (int i = 0; i < inventory.getSize() && !isEmpty(remaining); i++) {
            ItemStack existing = inventory.getItem(i);
            if (isEmpty(existing) || !canStacksMerge(existing, remaining)) {
                continue;
            }

            int max = Math.min(existing.getMaxStackSize(), inventory.getMaxStackSize());
            int canMove = Math.min(max - existing.getAmount(), remaining.getAmount());
            if (canMove <= 0) {
                continue;
            }

            existing.setAmount(existing.getAmount() + canMove);
            inventory.setItem(i, existing);

            remaining.setAmount(remaining.getAmount() - canMove);
            if (remaining.getAmount() <= 0) {
                remaining = null;
            }
        }

        for (int i = 0; i < inventory.getSize() && !isEmpty(remaining); i++) {
            ItemStack existing = inventory.getItem(i);
            if (!isEmpty(existing)) {
                continue;
            }

            int move = Math.min(remaining.getAmount(), Math.min(remaining.getMaxStackSize(), inventory.getMaxStackSize()));
            ItemStack moved = remaining.clone();
            moved.setAmount(move);
            inventory.setItem(i, moved);

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
        if (isEmpty(stack) || !Tag.SHULKER_BOXES.isTagged(stack.getType())) {
            return false;
        }

        ItemMeta meta = stack.getItemMeta();
        return meta instanceof BlockStateMeta blockStateMeta && blockStateMeta.getBlockState() instanceof ShulkerBox;
    }

    private boolean canReplaceInventoryItem(ItemStack item) {
        if (isEmpty(item)) {
            return false;
        }

        Material type = item.getType();
        if (isHoe(type) || isAxe(type) || isShovel(type)) {
            return false;
        }

        if (!type.isBlock()) {
            return false;
        }

        return !Tag.SHULKER_BOXES.isTagged(type) && type != Material.ENDER_CHEST;
    }

    private boolean canStacksMerge(ItemStack first, ItemStack second) {
        return !isEmpty(first) && !isEmpty(second) && first.isSimilar(second);
    }

    private boolean isEmpty(ItemStack stack) {
        return stack == null || stack.getType() == Material.AIR || stack.getAmount() <= 0;
    }

    private ItemStack cloneOrNull(ItemStack stack) {
        return isEmpty(stack) ? null : stack.clone();
    }

    private ItemStack copySingle(ItemStack stack) {
        ItemStack single = stack.clone();
        single.setAmount(1);
        return single;
    }

    private boolean isHoe(Material material) {
        return switch (material) {
            case WOODEN_HOE, STONE_HOE, IRON_HOE, GOLDEN_HOE, DIAMOND_HOE, NETHERITE_HOE -> true;
            default -> false;
        };
    }

    private boolean isAxe(Material material) {
        return switch (material) {
            case WOODEN_AXE, STONE_AXE, IRON_AXE, GOLDEN_AXE, DIAMOND_AXE, NETHERITE_AXE -> true;
            default -> false;
        };
    }

    private boolean isShovel(Material material) {
        return switch (material) {
            case WOODEN_SHOVEL, STONE_SHOVEL, IRON_SHOVEL, GOLDEN_SHOVEL, DIAMOND_SHOVEL, NETHERITE_SHOVEL -> true;
            default -> false;
        };
    }

    private void syncPlayerInventory(Player player) {
        player.updateInventory();
    }

    private void loadServerConfig() {
        allowedExchangeDimensions.clear();
        linkedContainerExchangeMode = LinkedContainerExchangeMode.CROSS_DIMENSION;
        linkedContainerScanLimit = DEFAULT_LINKED_CONTAINER_SCAN_LIMIT;

        if (!Files.exists(serverConfigPath)) {
            saveDefaultServerConfig();
            plugin.getLogger().info("Server config created: " + serverConfigPath);
            return;
        }

        try {
            JsonObject root = GSON.fromJson(Files.readString(serverConfigPath), JsonObject.class);
            if (root == null
                    || !root.has(ALLOWED_EXCHANGE_DIMENSIONS_KEY)
                    || !root.get(ALLOWED_EXCHANGE_DIMENSIONS_KEY).isJsonArray()) {
                saveDefaultServerConfig();
                return;
            }

            if (root.has(LINKED_CONTAINER_EXCHANGE_MODE_KEY)) {
                linkedContainerExchangeMode = LinkedContainerExchangeMode.fromString(
                        root.get(LINKED_CONTAINER_EXCHANGE_MODE_KEY).getAsString(),
                        plugin
                );
            }

            if (root.has(LINKED_CONTAINER_SCAN_LIMIT_KEY)) {
                linkedContainerScanLimit = parseLinkedContainerScanLimit(root.get(LINKED_CONTAINER_SCAN_LIMIT_KEY));
            } else {
                root.addProperty(LINKED_CONTAINER_SCAN_LIMIT_KEY, linkedContainerScanLimit);
                saveServerConfig(root);
            }

            JsonArray allowedDimensions = root.getAsJsonArray(ALLOWED_EXCHANGE_DIMENSIONS_KEY);
            for (JsonElement element : allowedDimensions) {
                if (!element.isJsonPrimitive()) {
                    continue;
                }

                String dimension = element.getAsString();
                if (dimension == null || dimension.isBlank()) {
                    continue;
                }

                if (isValidDimensionId(dimension)) {
                    allowedExchangeDimensions.add(dimension);
                } else {
                    plugin.getLogger().warning("Ignoring invalid TakeItOut exchange dimension in server config: " + dimension);
                }
            }

            plugin.getLogger().info(
                    "Server config loaded: linkedContainerExchangeMode=" + linkedContainerExchangeMode.id
                            + ", linkedContainerScanLimit=" + linkedContainerScanLimit
                            + ", allowedExchangeDimensions="
                            + (allowedExchangeDimensions.isEmpty() ? "all" : allowedExchangeDimensions)
            );
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to load TakeItOut server config, using defaults", exception);
        }
    }

    private int parseLinkedContainerScanLimit(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            plugin.getLogger().warning(
                    "Invalid TakeItOut linked container scan limit '" + element
                            + "', using " + DEFAULT_LINKED_CONTAINER_SCAN_LIMIT
            );
            return DEFAULT_LINKED_CONTAINER_SCAN_LIMIT;
        }

        int limit;
        try {
            limit = element.getAsInt();
        } catch (Exception exception) {
            plugin.getLogger().warning(
                    "Invalid TakeItOut linked container scan limit '" + element
                            + "', using " + DEFAULT_LINKED_CONTAINER_SCAN_LIMIT
            );
            return DEFAULT_LINKED_CONTAINER_SCAN_LIMIT;
        }

        if (limit < 1) {
            plugin.getLogger().warning(
                    "Invalid TakeItOut linked container scan limit '" + limit
                            + "', using " + DEFAULT_LINKED_CONTAINER_SCAN_LIMIT
            );
            return DEFAULT_LINKED_CONTAINER_SCAN_LIMIT;
        }

        return limit;
    }

    private void saveDefaultServerConfig() {
        JsonObject root = new JsonObject();
        root.addProperty(LINKED_CONTAINER_EXCHANGE_MODE_KEY, linkedContainerExchangeMode.id);
        root.addProperty(LINKED_CONTAINER_SCAN_LIMIT_KEY, linkedContainerScanLimit);
        root.add(ALLOWED_EXCHANGE_DIMENSIONS_KEY, new JsonArray());

        saveServerConfig(root);
    }

    private void saveServerConfig(JsonObject root) {
        try {
            Files.createDirectories(serverConfigPath.getParent());
            Files.writeString(serverConfigPath, GSON.toJson(root));
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to write TakeItOut server config", exception);
        }
    }

    private record WorldContainerItemCount(ItemStack stack, int count) {
    }

    private record WorldContainerSource(String dimension, long position) {
    }

    private record WorldContainerContents(WorldContainerSource source, List<WorldContainerItemCount> items) {
    }

    private record PackedBlockPos(int x, int y, int z) {
        private static PackedBlockPos unpack(long value) {
            int x = (int) (value >> 38);
            int y = (int) (value << 52 >> 52);
            int z = (int) (value << 26 >> 38);
            return new PackedBlockPos(x, y, z);
        }
    }

    private enum LinkedContainerExchangeMode {
        DISABLED("disabled"),
        SAME_DIMENSION("same_dimension"),
        CROSS_DIMENSION("cross_dimension");

        private final String id;

        LinkedContainerExchangeMode(String id) {
            this.id = id;
        }

        private static LinkedContainerExchangeMode fromString(String value, JavaPlugin plugin) {
            if (value != null) {
                for (LinkedContainerExchangeMode mode : values()) {
                    if (mode.id.equalsIgnoreCase(value) || mode.name().equalsIgnoreCase(value)) {
                        return mode;
                    }
                }
            }

            plugin.getLogger().warning("Invalid linked container exchange mode '" + value + "', using " + CROSS_DIMENSION.id);
            return CROSS_DIMENSION;
        }
    }

    private static final class PacketReader {
        private final byte[] data;
        private int index;

        private PacketReader(byte[] data) {
            this.data = data;
        }

        private boolean hasRemaining() {
            return index < data.length;
        }

        private int readInt() {
            ensureAvailable(4);
            int value = ((data[index] & 0xFF) << 24)
                    | ((data[index + 1] & 0xFF) << 16)
                    | ((data[index + 2] & 0xFF) << 8)
                    | (data[index + 3] & 0xFF);
            index += 4;
            return value;
        }

        private long readLong() {
            ensureAvailable(8);
            long value = 0L;
            for (int i = 0; i < 8; i++) {
                value = (value << 8) | (data[index + i] & 0xFFL);
            }
            index += 8;
            return value;
        }

        private boolean readBoolean() {
            ensureAvailable(1);
            return data[index++] != 0;
        }

        private String readString() {
            int byteLength = readVarInt();
            if (byteLength < 0 || byteLength > Short.MAX_VALUE * 4) {
                throw new IllegalArgumentException("String length out of range: " + byteLength);
            }

            ensureAvailable(byteLength);
            String value = new String(data, index, byteLength, StandardCharsets.UTF_8);
            index += byteLength;
            if (value.length() > Short.MAX_VALUE) {
                throw new IllegalArgumentException("String is too long");
            }
            return value;
        }

        private int readVarInt() {
            int value = 0;
            int position = 0;

            while (true) {
                ensureAvailable(1);
                byte current = data[index++];
                value |= (current & 0x7F) << position;
                if ((current & 0x80) == 0) {
                    return value;
                }

                position += 7;
                if (position >= 32) {
                    throw new IllegalArgumentException("VarInt is too big");
                }
            }
        }

        private List<WorldContainerSource> readWorldContainerSources(int maxSize) {
            int length = readVarInt();
            if (length < 0 || length > maxSize) {
                throw new IllegalArgumentException("World container source list length out of range: " + length);
            }

            List<WorldContainerSource> result = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                result.add(new WorldContainerSource(readString(), readLong()));
            }
            return result;
        }

        private byte[] remainingBytes() {
            return Arrays.copyOfRange(data, index, data.length);
        }

        private void skip(int length) {
            ensureAvailable(length);
            index += length;
        }

        private void ensureAvailable(int length) {
            if (index + length > data.length) {
                throw new IllegalArgumentException("Unexpected end of plugin message");
            }
        }
    }

    private static final class PacketWriter {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        private void writeInt(int value) {
            output.write((value >>> 24) & 0xFF);
            output.write((value >>> 16) & 0xFF);
            output.write((value >>> 8) & 0xFF);
            output.write(value & 0xFF);
        }

        private void writeLong(long value) {
            for (int shift = 56; shift >= 0; shift -= 8) {
                output.write((int) ((value >>> shift) & 0xFF));
            }
        }

        private void writeBoolean(boolean value) {
            output.write(value ? 1 : 0);
        }

        private void writeString(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > Short.MAX_VALUE * 4) {
                throw new IllegalArgumentException("String is too long");
            }

            writeVarInt(bytes.length);
            writeBytes(bytes);
        }

        private void writeVarInt(int value) {
            int current = value;
            while ((current & -128) != 0) {
                output.write((current & 127) | 128);
                current >>>= 7;
            }
            output.write(current);
        }

        private void writeBytes(byte[] bytes) {
            output.writeBytes(bytes);
        }

        private void writeWorldContainerSource(WorldContainerSource source) {
            writeString(source.dimension());
            writeLong(source.position());
        }

        private byte[] toByteArray() {
            return output.toByteArray();
        }
    }

    private static final class MinecraftItemStackCodec {
        private final Object registryAccess;
        private final Constructor<?> registryFriendlyByteBufConstructor;
        private final Method byteBufReadableBytesMethod;
        private final Method byteBufReadBytesMethod;
        private final Method byteBufReaderIndexMethod;
        private final Method unpooledBufferMethod;
        private final Method unpooledWrappedBufferMethod;
        private final Method asNmsCopyMethod;
        private final Method asBukkitCopyMethod;
        private final Method streamCodecEncodeMethod;
        private final Method streamCodecDecodeMethod;
        private final Object streamCodec;

        private MinecraftItemStackCodec() {
            try {
                Object server = Bukkit.getServer();
                Class<?> craftServerClass = server.getClass();
                Object minecraftServer = craftServerClass.getMethod("getServer").invoke(server);
                Object registryAccessTemp;
                try {
                    registryAccessTemp = minecraftServer.getClass().getMethod("registryAccess").invoke(minecraftServer);
                } catch (NoSuchMethodException e) {
                    // 1.21.5+: registryAccess() removed from MinecraftServer, get it from a world (ServerLevel)
                    org.bukkit.World firstWorld = Bukkit.getWorlds().get(0);
                    Object serverLevel = firstWorld.getClass().getMethod("getHandle").invoke(firstWorld);
                    registryAccessTemp = serverLevel.getClass().getMethod("registryAccess").invoke(serverLevel);
                }
                registryAccess = registryAccessTemp;

                String craftBukkitPackage = craftServerClass.getPackage().getName();
                Class<?> craftItemStackClass = Class.forName(craftBukkitPackage + ".inventory.CraftItemStack");
                Class<?> nmsItemStackClass = Class.forName("net.minecraft.world.item.ItemStack");
                Class<?> streamCodecClass = Class.forName("net.minecraft.network.codec.StreamCodec");
                Class<?> byteBufClass = Class.forName("io.netty.buffer.ByteBuf");
                Class<?> registryAccessClass = Class.forName("net.minecraft.core.RegistryAccess");
                Class<?> registryFriendlyByteBufClass = Class.forName("net.minecraft.network.RegistryFriendlyByteBuf");
                Class<?> unpooledClass = Class.forName("io.netty.buffer.Unpooled");

                registryFriendlyByteBufConstructor = registryFriendlyByteBufClass.getConstructor(byteBufClass, registryAccessClass);
                unpooledBufferMethod = unpooledClass.getMethod("buffer");
                unpooledWrappedBufferMethod = unpooledClass.getMethod("wrappedBuffer", byte[].class);
                byteBufReadableBytesMethod = byteBufClass.getMethod("readableBytes");
                byteBufReadBytesMethod = byteBufClass.getMethod("readBytes", byte[].class);
                byteBufReaderIndexMethod = byteBufClass.getMethod("readerIndex");

                asNmsCopyMethod = craftItemStackClass.getMethod("asNMSCopy", ItemStack.class);
                asBukkitCopyMethod = craftItemStackClass.getMethod("asBukkitCopy", nmsItemStackClass);

                streamCodec = nmsItemStackClass.getField("STREAM_CODEC").get(null);
                streamCodecEncodeMethod = streamCodecClass.getMethod("encode", Object.class, Object.class);
                streamCodecDecodeMethod = streamCodecClass.getMethod("decode", Object.class);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Unable to initialize Minecraft item stack codec bridge", exception);
            }
        }

        private ItemStack decode(PacketReader reader) {
            try {
                Object byteBuf = unpooledWrappedBufferMethod.invoke(null, (Object) reader.remainingBytes());
                Object registryFriendlyByteBuf = registryFriendlyByteBufConstructor.newInstance(byteBuf, registryAccess);
                Object decoded = streamCodecDecodeMethod.invoke(streamCodec, registryFriendlyByteBuf);
                int consumed = (Integer) byteBufReaderIndexMethod.invoke(byteBuf);
                reader.skip(consumed);
                return (ItemStack) asBukkitCopyMethod.invoke(null, decoded);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Unable to decode item stack from plugin message", exception);
            }
        }

        private void encode(PacketWriter writer, ItemStack stack) {
            try {
                Object byteBuf = unpooledBufferMethod.invoke(null);
                Object registryFriendlyByteBuf = registryFriendlyByteBufConstructor.newInstance(byteBuf, registryAccess);
                Object nmsStack = asNmsCopyMethod.invoke(null, stack);
                streamCodecEncodeMethod.invoke(streamCodec, registryFriendlyByteBuf, nmsStack);
                int readableBytes = (Integer) byteBufReadableBytesMethod.invoke(byteBuf);
                byte[] bytes = new byte[readableBytes];
                byteBufReadBytesMethod.invoke(byteBuf, (Object) bytes);
                writer.writeBytes(bytes);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Unable to encode item stack into plugin message", exception);
            }
        }
    }
}
