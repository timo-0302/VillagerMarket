package net.bestemor.villagermarket.shop;

import net.bestemor.core.config.ConfigManager;
import net.bestemor.core.config.ListBuilder;
import net.bestemor.core.config.VersionUtils;
import net.bestemor.villagermarket.VMPlugin;
import net.bestemor.villagermarket.menu.EditItemMenu;
import net.bestemor.villagermarket.menu.StorageHolder;
import net.bestemor.villagermarket.utils.VMUtils;
import net.milkbowl.vault.economy.Economy;
import org.apache.commons.lang.WordUtils;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static net.bestemor.villagermarket.shop.ItemMode.*;

public class ShopItem {

    public enum LimitMode {
        SERVER,
        PLAYER
    }

    private final VMPlugin plugin;
    private final ItemStack item;

    private final int slot;

    private boolean isAdmin;

    private List<String> editorLore = new ArrayList<>();

    private BigDecimal sellPrice;
    private BigDecimal buyPrice;
    private int amount = 0;
    private int itemTradeAmount = 0;
    private ItemStack itemTrade;

    //Limit variables
    private int limit = 0;
    private LimitMode limitMode = LimitMode.PLAYER;
    private String cooldown = "never";
    private int serverTrades = 0;
    private Instant nextReset;
    private final Map<UUID, Integer> playerLimits = new HashMap<>();

    int storageAmount = 0;
    int available = -1;

    private ItemMode mode = SELL;

    private final List<String> commands = new ArrayList<>();

    public ShopItem(VMPlugin plugin, ItemStack item, int slot) {
        this.plugin = plugin;
        this.slot = slot;
        this.item = item;
        this.amount = item.getAmount();
    }
    public ShopItem(VMPlugin plugin, ConfigurationSection section) {
        this.plugin = plugin;
        this.slot = Integer.parseInt(section.getName());

        this.item = section.getItemStack("item");
        if (item == null) {
            throw new NullPointerException("ItemStack is null!");
        }
        this.amount = section.getInt("amount") == 0 ? item.getAmount() : section.getInt("amount");

        Object trade = section.get("price");
        double d = section.getDouble("price");

        if (d != 0) {
            this.sellPrice = new BigDecimal(String.valueOf(d));
        } else if (trade instanceof ItemStack) {
            this.itemTrade = (ItemStack) trade;
            this.itemTradeAmount = section.getInt("trade_amount") == 0 ? itemTrade.getAmount() : section.getInt("trade_amount");
            this.sellPrice = BigDecimal.ZERO;
        }
        this.buyPrice = new BigDecimal(String.valueOf(section.getDouble("buy_price")));

        section.getStringList("command");
        for (String command : section.getStringList("command")) {
            addCommand(command);
        }

        this.mode = ItemMode.valueOf(section.getString("mode"));
        this.limit = section.getInt("buy_limit");
        this.limitMode = section.getString("limit_mode") == null ? LimitMode.PLAYER : LimitMode.valueOf(section.getString("limit_mode"));
        this.cooldown = section.getString("cooldown");
        this.serverTrades = section.getInt("server_trades");

        if (this.cooldown != null && !this.cooldown.equals("never")) {
            this.nextReset = Instant.ofEpochSecond(section.getLong("next_reset"));
        }

        ConfigurationSection limits = section.getConfigurationSection("limits");
        if (limits != null) {
            for (String uuid : limits.getKeys(false)) {
                playerLimits.put(UUID.fromString(uuid), limits.getInt(uuid));
            }
        }
    }

    public BigDecimal getSellPrice() {
        return sellPrice == null ? BigDecimal.ZERO : sellPrice;
    }
    public BigDecimal getBuyPrice() {
        return mode != BUY_AND_SELL ? getSellPrice() : buyPrice == null ? BigDecimal.ZERO : buyPrice;
    }
    public Material getType() { return item.getType(); }
    public int getSlot() {
        return slot;
    }
    public ItemMode getMode() {
        return mode;
    }
    public int getLimit() {
        return limit;
    }
    public int getAmount() { return amount; }
    public List<String> getCommands() {
        return commands;
    }
    public boolean isItemTrade() {
        return this.itemTrade != null;
    }
    public ItemStack getItemTrade() {
        return itemTrade;
    }
    public int getServerTrades() {
        return serverTrades;
    }
    public LimitMode getLimitMode() {
        return limitMode;
    }
    public String getCooldown() {
        return cooldown;
    }
    public Instant getNextReset() {
        return nextReset;
    }
    public int getItemTradeAmount() {
        return itemTradeAmount;
    }

    public Map<UUID, Integer> getPlayerLimits() {
        return playerLimits;
    }
    public void setAdmin(boolean admin) {
        isAdmin = admin;
    }
    public void setSellPrice(BigDecimal sellPrice) {
        this.sellPrice = sellPrice;
    }
    public void setBuyPrice(BigDecimal buyPrice) {
        this.buyPrice = buyPrice;
    }
    public void setLimit(int limit) {
        this.limit = limit;
    }
    public void setAmount(int amount) {
        this.item.setAmount(amount > item.getMaxStackSize() ? 1 : amount);
        this.amount = amount;
    }
    public void addCommand(String command) {
        this.mode = ItemMode.COMMAND;
        this.commands.add(command);
    }
    public void setItemTrade(ItemStack itemTrade, int amount) {
        this.itemTrade = itemTrade;
        this.itemTradeAmount = amount;
        if (itemTrade != null) {
            this.mode = SELL;
        }
    }

    public void resetCommand() {
        this.commands.clear();
    }


    public void setCooldown(String cooldown) {
        String amount = cooldown.substring(0, cooldown.length() - 1);
        String unit = cooldown.substring(cooldown.length() - 1);
        if (!VMUtils.isInteger(amount) || (!unit.equals("m") && !unit.equals("h") && !unit.equals("d"))) {
            this.cooldown = null;
            return;
        } else {
            this.cooldown = cooldown;
        }
        resetCooldown();
    }

    public void openEditor(Player player, VillagerShop shop, int page) {
        new EditItemMenu(plugin, shop, this, page).open(player);
    }

    public void cycleTradeMode() {
        if (!isItemTrade()) {
            switch (mode) {
                case SELL:
                    mode = ItemMode.BUY;
                    break;
                case BUY:
                    mode = BUY_AND_SELL;
                    break;
                case BUY_AND_SELL:
                    mode = isAdmin ? ItemMode.COMMAND : ItemMode.SELL;
                    break;
                case COMMAND:
                    mode = SELL;
            }
        }
    }
    public int getPlayerLimit(Player player) {
        return playerLimits.getOrDefault(player.getUniqueId(), 0);
    }
    public void incrementPlayerTrades(Player player) {
        playerLimits.put(player.getUniqueId(), getPlayerLimit(player) + 1);
    }
    public void incrementServerTrades() {
        serverTrades ++;
    }
    private void reloadData(VillagerShop shop) {
        if (shop instanceof PlayerShop) {
            PlayerShop playerShop = (PlayerShop) shop;
            this.storageAmount = playerShop.getStorageHolder().getAmount(item.clone());
        }
        this.available = shop.getAvailable(this);
    }

    private void resetCooldown() {
        Instant i = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        if (cooldown == null) {
            nextReset = Instant.ofEpochSecond(0);
            return;
        }
        String s = cooldown.substring(0, cooldown.length() - 1);
        if (!VMUtils.isInteger(s)) {
            nextReset = Instant.ofEpochSecond(0);
            cooldown = null;
            return;
        }

        int amount = Integer.parseInt(s);
        switch (cooldown.substring(cooldown.length() - 1)) {
            case "m":
                i = i.plus(amount, ChronoUnit.MINUTES);
                break;
            case "h":
                i = i.plus(amount, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);
                break;
            case "d":
                i = i.plus(amount, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS);
                break;
            default:

        }
        this.nextReset = i;
    }

    public void clearLimits() {
        this.playerLimits.clear();
        this.serverTrades = 0;
        resetCooldown();
    }
    public void cycleLimitMode() {
        limitMode = limitMode == LimitMode.SERVER ? LimitMode.PLAYER : LimitMode.SERVER;
    }

    public void reloadMeta(VillagerShop shop) {
        reloadData(shop);
        editorLore = getLore("edit_shopfront", mode, null);
    }

    public String getItemName() {
        return getItemName(item);
    }

    public boolean verifyPurchase(Player player, ItemMode verifyMode) {
        return verifyPurchase(player, verifyMode, null,null);
    }
    public boolean verifyPurchase(Player customer, ItemMode verifyMode, OfflinePlayer owner, StorageHolder storage) {

        if (owner != null && customer.getUniqueId().equals(owner.getUniqueId())) {
            customer.sendMessage(ConfigManager.getMessage("messages.cannot_" + (verifyMode == SELL ?  "buy_from" :"sell_to") + "_yourself"));
            //return false;
        }
        Economy economy = plugin.getEconomy();
        if (verifyMode == SELL && isItemTrade() && getAmountInventory(itemTrade, customer.getInventory()) < itemTradeAmount) {
            customer.sendMessage(ConfigManager.getMessage("messages.not_enough_in_inventory"));
            return false;
        }
        if (!isItemTrade() && verifyMode == SELL && storage != null && storage.getAmount(item.clone()) < getAmount()) {
            customer.sendMessage(ConfigManager.getMessage("messages.not_enough_stock"));
            return false;
        }
        if (!isItemTrade() && verifyMode == SELL && itemTrade == null && economy.getBalance(customer) < getSellPrice().doubleValue()) {
            customer.sendMessage(ConfigManager.getMessage("messages.not_enough_money"));
            return false;
        }
        if (!isItemTrade() && verifyMode == BUY && owner != null && itemTrade == null && economy.getBalance(owner) < getBuyPrice().doubleValue()) {
            customer.sendMessage(ConfigManager.getMessage("messages.owner_not_enough_money"));
            return false;
        }
        if (verifyMode == ItemMode.BUY && getAmountInventory(item.clone(), customer.getInventory()) < getAmount()) {
            customer.sendMessage(ConfigManager.getMessage("messages.not_enough_in_inventory"));
            return false;
        }
        if ((verifyMode == BUY || isItemTrade()) && available != -1 && getAmount() > available) {
            customer.sendMessage(ConfigManager.getMessage("messages.reached_" + (isItemTrade() ? "buy" : "sell") + "_limit"));
            return false;
        }
        boolean bypass = customer.hasPermission("villagermarket.bypass_limit");
        if (isAdmin && !bypass && limit > 0 && ((limitMode == LimitMode.SERVER && serverTrades >= limit) || (limitMode == LimitMode.PLAYER && getPlayerLimit(customer) >= limit))) {
            customer.sendMessage(ConfigManager.getMessage("messages.reached_" + (verifyMode == BUY ? "sell" : "buy") + "_limit"));
            return false;
        }
        return true;
    }

    private List<String> getLore(String path, ItemMode mode, Player p) {
        String typePath = (isAdmin ? "admin_shop." : "player_shop.");
        String modePath = isItemTrade() ? "trade" : mode.toString().toLowerCase();

        String reset = nextReset == null || nextReset.getEpochSecond() == 0 ? ConfigManager.getString("time.never") : ConfigManager.getTimeLeft(nextReset);
        String bought = String.valueOf(limitMode == LimitMode.SERVER || p == null ? serverTrades : getPlayerLimit(p));
        String limitInfo = limit == 0 ? ConfigManager.getString("quantity.unlimited") : String.valueOf(limit);

        String lorePath = "menus." + path + "." + typePath + (isAdmin && path.startsWith("edit") ? "standard" : modePath)  + "_lore";
        ListBuilder builder = ConfigManager.getListBuilder(lorePath)
                .replace("%amount%", String.valueOf(amount))
                .replace("%stock%", String.valueOf(storageAmount))
                .replace("%bought%", bought)
                .replace("%available%", String.valueOf(available))
                .replace("%mode%", ConfigManager.getString("menus.shopfront.modes." + modePath))
                .replace("%reset%", reset)
                .replace("%limit%", limitInfo);

        if (isItemTrade()) {
            builder.replace("%price%", getItemTradeAmount() + "x" + " " + getItemName(itemTrade));
        } else if (getSellPrice().equals(BigDecimal.ZERO)) {
            builder.replace("%price%", ConfigManager.getString("quantity.free"));
            builder.replace("%price_per_unit%", ConfigManager.getString("quantity.free"));
        } else if (mode != BUY_AND_SELL) {
            builder.replaceCurrency("%price%", getSellPrice());
            builder.replaceCurrency("%price_per_unit%", getSellPrice().divide(BigDecimal.valueOf(getAmount()), RoundingMode.HALF_UP));
        } else {
            boolean isCustomerMenu = path.equals("shopfront");
            builder.replaceCurrency("%buy_price%", isCustomerMenu ? getSellPrice() : getBuyPrice());
            builder.replaceCurrency("%sell_price%", isCustomerMenu ? getBuyPrice() : getSellPrice());
        }
        List<String> lore = builder.build();

        if (isAdmin && limit > 0) {
            int index = lore.indexOf("%limit_lore%");
            if (index != -1) {
                lore.remove(index);
                String type = isItemTrade() ? "buy" : mode.getInteractionType();
                lore.addAll(index, ConfigManager.getListBuilder("menus.shopfront.admin_shop." + type + "_limit_lore")
                        .replace("%reset%", reset)
                        .replace("%limit%", limitInfo)
                        .replace("%bought%", bought).build());
            }
        }
        lore.remove("%limit_lore%");

        return lore;
    }

    public ItemStack getEditorItem() {
        ItemStack i = getRawItem();
        ItemMeta m = i.getItemMeta();
        if (m != null && editorLore != null) {

            m.setLore(editorLore);
            i.setItemMeta(m);
        }
        return i;
    }
    public ItemStack getCustomerItem(Player p) {
        ItemStack i = getRawItem();
        ItemMeta m = i.getItemMeta();
        if (m != null) {
            m.setLore(getLore("shopfront", mode.inverted(), p));
            i.setItemMeta(m);
        }
        return i;
    }

    public ItemStack getRawItem() {
        return item.clone();
    }

    public String getItemTradeName() {
        return getItemName(itemTrade);
    }

    private int getAmountInventory(ItemStack itemStack, Inventory inventory) {
        int amount = 0;
        for (ItemStack storageStack : inventory.getContents()) {
            if (storageStack == null) { continue; }

            if (VMUtils.compareItems(storageStack, itemStack)) {
                amount = amount + storageStack.getAmount();
            }
        }
        return amount;
    }

    private String getItemName(ItemStack i) {
        ItemMeta m = i.getItemMeta();
        if (m != null && m.hasDisplayName()) {
            return m.getDisplayName();
        } else if (m != null && VersionUtils.getMCVersion() > 11 && m.hasLocalizedName()) {
            return m.getLocalizedName();
        } else {
            return WordUtils.capitalizeFully(i.getType().name().replaceAll("_", " "));
        }
    }
}
