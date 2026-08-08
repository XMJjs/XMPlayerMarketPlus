package top.mrxiaom.sweet.playermarket.gui.auction;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.mrxiaom.pluginbase.api.InventoryViewAccessor;
import top.mrxiaom.pluginbase.func.gui.IModifier;
import top.mrxiaom.pluginbase.func.gui.LoadedIcon;
import top.mrxiaom.pluginbase.gui.IGuiHolder;
import top.mrxiaom.pluginbase.utils.ListPair;
import top.mrxiaom.pluginbase.utils.Pair;
import top.mrxiaom.pluginbase.utils.Util;
import top.mrxiaom.sweet.playermarket.SweetPlayerMarket;
import top.mrxiaom.sweet.playermarket.auction.Auction;
import top.mrxiaom.sweet.playermarket.func.AbstractGuiModule;
import top.mrxiaom.sweet.playermarket.utils.ListX;
import top.mrxiaom.sweet.playermarket.utils.Utils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * 拍卖 GUI 抽象基类（仿 AbstractGuiSearch，但数据源为 Auction）。
 *
 * <p>YAML 布局中 {@code '拍'} 字符为动态拍卖槽（出现次数即列表索引），
 * {@code main-icons.拍} 配置拍卖物品展示与点击动作；
 * {@code '空'} 为无拍卖占位；其余字符走 other-icons。
 */
public abstract class AbstractAuctionGui extends AbstractGuiModule {
    protected final String filePath;
    protected LoadedIcon iconItem, iconEmpty;

    public AbstractAuctionGui(SweetPlayerMarket plugin, String file) {
        super(plugin, plugin.resolve("./gui/" + file));
        this.filePath = file;
    }

    @Override
    public String warningPrefix() {
        return "[" + filePath + "]";
    }

    @Override
    public void reloadConfig(MemoryConfiguration cfg) {
        String resourceFile = "gui/" + filePath;
        File guiFolder = plugin.resolve(cfg.getString("gui-folder", "./gui"));
        this.file = new File(guiFolder, filePath);
        if (!file.exists()) {
            plugin.saveResource(resourceFile, file);
        }
        super.reloadConfig(cfg);
        iconItem = Utils.requireIconNotNull(this, resourceFile, iconItem, "main-icons.拍");
        iconEmpty = Utils.requireIconNotNull(this, resourceFile, iconEmpty, "main-icons.空");
    }

    @Override
    protected void reloadMenuConfig(YamlConfiguration config) {
        iconItem = null;
        iconEmpty = null;
    }

    @Override
    protected void loadMainIcon(ConfigurationSection section, String id, LoadedIcon icon) {
        if (id.equals("拍")) iconItem = icon;
        if (id.equals("空")) iconEmpty = icon;
    }

    @NotNull
    public List<top.mrxiaom.pluginbase.api.IAction> getAuctionClickActions(@NotNull ClickType type) {
        switch (type) {
            case LEFT: return iconItem.leftClickCommands;
            case RIGHT: return iconItem.rightClickCommands;
            case SHIFT_LEFT: return iconItem.shiftLeftClickCommands;
            case SHIFT_RIGHT: return iconItem.shiftRightClickCommands;
            case DROP: return iconItem.dropCommands;
            default: return new ArrayList<>();
        }
    }

    /** 子类可覆盖：同一拍卖在不同界面显示不同图标（如"我的拍卖"里的待领） */
    protected LoadedIcon decideIconByAuction(AuctionGui instance, Player player, Auction auction, ListPair<String, Object> r) {
        return iconItem;
    }

    @Override
    protected ItemStack applyMainIcon(IGuiHolder instance, Player player, char id, int index, int appearTimes) {
        AuctionGui gui = (AuctionGui) instance;
        if (id == '拍') {
            int i = appearTimes - 1;
            Auction auction = gui.getAuction(i);
            if (auction == null) {
                return iconEmpty.generateIcon(player);
            }
            ItemStack baseItem = auction.item();
            ListPair<String, Object> r = new ListPair<>();
            r.addAll(gui.commonReplacements);
            applyAuctionPlaceholders(plugin, auction, player, r);
            IModifier<String> displayModifier = oldName -> Pair.replace(oldName, r);
            IModifier<List<String>> loreModifier = oldLore -> {
                List<String> lore = new ArrayList<>();
                for (String s : oldLore) {
                    if (s.equals("item lore")) {
                        lore.addAll(top.mrxiaom.pluginbase.utils.AdventureItemStack.getItemLoreAsMiniMessage(baseItem));
                        continue;
                    }
                    String result = Utils.replaceOrNull(player, s, r);
                    if (result != null) {
                        if (!result.isEmpty()) lore.add(result);
                        continue;
                    }
                    lore.add(Pair.replace(s, r));
                }
                return lore;
            };
            LoadedIcon icon = decideIconByAuction(gui, player, auction, r);
            ItemStack built = icon.generateIcon(baseItem, null, displayModifier, loreModifier);
            return built;
        }
        return null;
    }

    @Override
    protected @Nullable ItemStack applyOtherIcon(IGuiHolder instance, Player player, char id, int index, int appearTimes, LoadedIcon icon) {
        AuctionGui gui = (AuctionGui) instance;
        IModifier<String> displayModifier = oldName -> Pair.replace(oldName, gui.commonReplacements);
        IModifier<List<String>> loreModifier = oldLore -> Pair.replace(oldLore, gui.commonReplacements);
        return icon.generateIcon(player, displayModifier, loreModifier);
    }

    /** 拍卖占位符注入（供 YAML lore 使用） */
    public static void applyAuctionPlaceholders(SweetPlayerMarket plugin, Auction auction, Player player, ListPair<String, Object> r) {
        String display = plugin.getItemDisplayName(auction.item());
        if (display == null) display = auction.item().getType().name();
        r.add("%item%", display);
        r.add("%auction_seller%", auction.sellerName());
        r.add("%auction_start_price%", plugin.displayNames().formatMoney(auction.startPrice()));
        r.add("%auction_current_bid%", plugin.displayNames().formatMoney(auction.currentBid()));
        r.add("%auction_buy_now%", auction.buyNowPrice() > 0
                ? plugin.displayNames().formatMoney(auction.buyNowPrice())
                : org.bukkit.ChatColor.RED + "无");
        r.add("%auction_bid_count%", auction.params().getInt("bid-count", 0));
        r.add("%auction_highest_bidder%", auction.highestBidderName() != null
                ? auction.highestBidderName()
                : (auction.highestBidderId() != null ? auction.highestBidderId() : "暂无"));
        r.add("%auction_time_left%", formatTimeLeft(auction));
        r.add("%auction_end_time%", plugin.toString(auction.endAt()));
        r.add("%auction_create_time%", plugin.toString(auction.createdAt()));
        r.add("%auction_auto_extend%", auction.autoExtend() ? "开启" : "关闭");
        r.add("%auction_status%", auction.status().name());
        r.add("%auction_next_bid%", plugin.displayNames().formatMoney(auction.nextBidAmount()));
    }

    public static String formatTimeLeft(Auction auction) {
        long millis = java.time.Duration.between(java.time.LocalDateTime.now(), auction.endAt()).toMillis();
        if (millis <= 0) return org.bukkit.ChatColor.RED + "已结束";
        long days = millis / 86400000L;
        long hours = (millis % 86400000L) / 3600000L;
        long minutes = (millis % 3600000L) / 60000L;
        long seconds = (millis % 60000L) / 1000L;
        if (days > 0) return days + "天" + hours + "小时";
        if (hours > 0) return hours + "小时" + minutes + "分";
        if (minutes > 0) return minutes + "分" + seconds + "秒";
        return seconds + "秒";
    }

    /** 拍卖 GUI 实例：分页 + 刷新 + 点击分发 */
    public abstract class AuctionGui extends Gui implements top.mrxiaom.sweet.playermarket.gui.api.IGuiRefreshable,
            top.mrxiaom.sweet.playermarket.gui.api.IGuiPageable {
        public final SweetPlayerMarket plugin = AbstractAuctionGui.this.plugin;
        protected final ListX<Auction> auctions = new ListX<>();
        protected final int slotsSize;
        protected int page = 1;
        protected int totalCount = 0;   // doSearch 时由子类填充，供标题分页显示（避免主线程查库）
        protected boolean actionLock = false;
        protected final ListPair<String, Object> commonReplacements = new ListPair<>();
        protected String sort = "time_left";

        protected AuctionGui(Player player) {
            super(player, guiTitle, guiInventory);
            int size = 0;
            for (char c : super.inventory) {
                if (c == '拍') size++;
            }
            this.slotsSize = size;
        }

        protected void postInit() {
            doSearch();
        }

        @Nullable
        public Auction getAuction(int index) {
            return index < 0 || index >= auctions.size() ? null : auctions.get(index);
        }

        public void setAuction(int index, Auction auction) {
            if (index < 0 || index >= auctions.size()) return;
            auctions.set(index, auction);
        }

        public int getSlotsSize() { return slotsSize; }
        public int getPage() { return page; }
        public String getSort() { return sort; }

        /** 子类实现：把当前页拍卖装入 auctions */
        protected abstract void doSearch();

        @Override
        public void refreshGui() {
            actionLock = true;
            plugin.getScheduler().runTaskAsync(() -> {
                doSearch();
                updateInventory(getInventory());
                Util.submitInvUpdate(player);
            });
        }

        @Override
        public void turnPageUp(int pages) {
            if (this.page - pages < 1) return;
            actionLock = true;
            plugin.getScheduler().runTaskAsync(() -> {
                this.page -= pages;
                doSearch();
                plugin.getScheduler().runTask(this::open);
            });
        }

        @Override
        public void turnPageDown(int pages) {
            actionLock = true;
            plugin.getScheduler().runTaskAsync(() -> {
                this.page += pages;
                doSearch(); // 统一走 doSearch：刷新列表 + totalCount（分页标题同步）
                plugin.getScheduler().runTask(this::open);
            });
        }

        /** 子类实现：获取指定页的拍卖列表 */
        protected abstract List<Auction> fetchPage(int page, int size);

        /** 子类可覆写：刷新 commonReplacements（如创建向导的状态显示） */
        protected void updateReplacements() {
        }

        @Override
        public void updateInventory(BiConsumer<Integer, ItemStack> setItem) {
            updateReplacements();
            super.updateInventory(setItem);
            actionLock = false;
        }

        @Override
        protected Inventory create(int size, String title) {
            int maxPage = Math.max(1, (int) Math.ceil((double) totalCount / slotsSize));
            return super.create(size, Pair.replace0(title,
                    Pair.of("%page%", page),
                    Pair.of("%max_page%", maxPage)));
        }

        @Override
        public void onClick(
                InventoryAction action, ClickType click,
                InventoryType.SlotType slotType, int slot,
                ItemStack currentItem, ItemStack cursor,
                InventoryViewAccessor view, InventoryClickEvent event
        ) {
            event.setCancelled(true);
            if (actionLock) return;
            Character clickedId = getClickedId(slot);
            if (clickedId == null) return;
            actionLock = true;
            try {
                if (clickedId == '拍') {
                    int i = getAppearTimes(clickedId, slot) - 1;
                    Auction auction = getAuction(i);
                    if (auction == null) {
                        return;
                    }
                    onClickAuction(action, click, slotType, slot, auction, i, view, event);
                    return;
                }
                handleOtherClick(click, clickedId);
            } finally {
                // 任何异常/正常路径都复位点击锁，防止 GUI 卡死
                actionLock = false;
            }
        }

        /** 点击拍卖槽位（子类实现：打开详情/领取/取消等） */
        protected abstract void onClickAuction(
                InventoryAction action, ClickType click,
                InventoryType.SlotType slotType, int slot,
                Auction auction, int index,
                InventoryViewAccessor view, InventoryClickEvent event);

        @Override
        public void handleOtherClick(ClickType type, Character id) {
            if (id != null) {
                LoadedIcon icon = otherIcons.get(id);
                if (icon != null) {
                    plugin.getScheduler().runTask(() -> {
                        // try-finally 保证异常下 actionLock 复位（防止 GUI 卡死）
                        try {
                            icon.click(player, type);
                        } finally {
                            actionLock = false;
                        }
                    });
                    return;
                }
            }
            actionLock = false;
        }

        /** 执行拍卖动作（携带 __internal__auction 上下文） */
        protected void runAuctionActions(ClickType click, Auction auction, int index) {
            ListPair<String, Object> r = new ListPair<>();
            r.add("__internal__auction", auction);
            r.add("__internal__index", index);
            plugin.run(player, getAuctionClickActions(click), r);
        }

        protected void refresh() {
            plugin.getScheduler().runTask(this::open);
        }
    }
}
