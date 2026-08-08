package top.mrxiaom.sweet.playermarket.gui.auction;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import top.mrxiaom.pluginbase.api.InventoryViewAccessor;
import top.mrxiaom.pluginbase.func.gui.LoadedIcon;
import top.mrxiaom.pluginbase.utils.ListPair;
import top.mrxiaom.pluginbase.utils.Pair;
import top.mrxiaom.sweet.playermarket.SweetPlayerMarket;
import top.mrxiaom.sweet.playermarket.auction.Auction;
import top.mrxiaom.sweet.playermarket.auction.AuctionMessages;
import top.mrxiaom.sweet.playermarket.auction.AuctionService;
import top.mrxiaom.sweet.playermarket.utils.Prompter;

import java.util.Collections;
import java.util.List;

/**
 * 创建拍卖向导界面。
 *
 * <p>交互（YAML 外观 + 代码驱动）：
 * <ul>
 *   <li>'拍'：显示手持物品（点击更新为当前手持物品）</li>
 *   <li>'价'：左键 +10 / 右键 +1 / Shift+左键 输入自定义起拍价</li>
 *   <li>'口'：设置一口价（0=无）</li>
 *   <li>'时'：循环选择时长 1h/6h/24h/48h/72h/168h</li>
 *   <li>'延'：切换自动延期开关</li>
 *   <li>'确'：确认创建</li>
 *   <li>'返'：返回主菜单</li>
 * </ul>
 * 状态占位符：%create_price% %create_buy_now% %create_duration% %create_auto_extend%
 */
public class GuiAuctionCreate extends AbstractAuctionGui {
    private static final long[] DURATIONS = {60, 6 * 60, 24 * 60, 48 * 60, 72 * 60, 7 * 24 * 60};

    public GuiAuctionCreate(SweetPlayerMarket plugin) {
        super(plugin, "auction-create.yml");
    }

    public static GuiAuctionCreate inst() {
        return instanceOf(GuiAuctionCreate.class);
    }

    public static void open(Player player) {
        inst().new Impl(player).open();
    }

    public class Impl extends AuctionGui {
        private double startPrice = 1.0;
        private double buyNowPrice = 0;
        private long durationMinutes = 24 * 60;
        private int durationIndex = 2;
        private boolean autoExtend = true;

        protected Impl(Player player) {
            super(player);
            postInit();
        }

        public double startPrice() { return startPrice; }
        public double buyNowPrice() { return buyNowPrice; }
        public long durationMinutes() { return durationMinutes; }
        public boolean autoExtend() { return autoExtend; }

        @Override
        protected void doSearch() {
            this.totalCount = 0; // 无动态拍卖数据
            // 将当前手持物品包装为展示用拍卖，供 '拍' 槽位预览（物品/价格/时长即时反映）
            this.auctions.clear();
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand != null && !hand.getType().isAir()) {
                this.auctions.add(Auction.builder(plugin, plugin.getKey(player), player.getName())
                        .item(hand.clone())
                        .startPrice(startPrice)
                        .buyNowPrice(buyNowPrice)
                        .endAt(java.time.LocalDateTime.now().plusMinutes(durationMinutes))
                        .autoExtend(autoExtend)
                        .extendMinutes(instanceOf(top.mrxiaom.sweet.playermarket.auction.AuctionConfig.class).extendMinutes())
                        .build());
            }
        }

        @Override
        protected List<Auction> fetchPage(int page, int size) {
            return Collections.emptyList();
        }

        @Override
        protected void updateReplacements() {
            commonReplacements.clear();
            commonReplacements.add("%create_price%", plugin.displayNames().formatMoney(startPrice));
            commonReplacements.add("%create_buy_now%", buyNowPrice > 0
                    ? plugin.displayNames().formatMoney(buyNowPrice) : "无");
            commonReplacements.add("%create_duration%", durationMinutes / 60.0 % 1 == 0
                    ? (durationMinutes / 60) + "小时" : durationMinutes + "分钟");
            commonReplacements.add("%create_auto_extend%", autoExtend ? "开启" : "关闭");
        }

        @Override
        protected void onClickAuction(InventoryAction action, ClickType click,
                                      InventoryType.SlotType slotType, int slot,
                                      Auction auction, int index,
                                      InventoryViewAccessor view, InventoryClickEvent event) {
            // 拍 槽：更新为当前手持物品（仅刷新预览，保留已设置的参数）
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand == null || hand.getType().isAir()) {
                AuctionMessages.create__no_item.tm(player);
                return;
            }
            refreshState();
        }

        @Override
        public void handleOtherClick(ClickType type, Character id) {
            if (id == null) {
                actionLock = false;
                return;
            }
            plugin.getScheduler().runTask(() -> {
                actionLock = false;
                switch (id) {
                    case '价': { // 起拍价
                        if (type.isShiftClick()) {
                            AuctionMessages.bid__prompt.tm(player);
                            Prompter.chat(player, input -> {
                                Double v = parseDouble(input);
                                if (v != null && v > 0) {
                                    startPrice = v;
                                    refreshState();
                                }
                            }, null);
                        } else if (type == ClickType.RIGHT) {
                            startPrice = Math.max(0, startPrice + 1);
                            refreshState();
                        } else {
                            startPrice += 10;
                            refreshState();
                        }
                        break;
                    }
                    case '口': { // 一口价
                        if (type.isShiftClick()) {
                            AuctionMessages.bid__prompt.tm(player);
                            Prompter.chat(player, input -> {
                                Double v = parseDouble(input);
                                buyNowPrice = v != null && v > 0 ? v : 0;
                                refreshState();
                            }, null);
                        } else {
                            buyNowPrice = buyNowPrice > 0 ? 0 : startPrice * 2;
                            refreshState();
                        }
                        break;
                    }
                    case '时': { // 时长
                        durationIndex = (durationIndex + 1) % DURATIONS.length;
                        durationMinutes = DURATIONS[durationIndex];
                        refreshState();
                        break;
                    }
                    case '延': { // 自动延期
                        autoExtend = !autoExtend;
                        refreshState();
                        break;
                    }
                    case '确': { // 确认创建
                        ItemStack hand = player.getInventory().getItemInMainHand();
                        if (hand == null || hand.getType().isAir()) {
                            AuctionMessages.create__no_item.tm(player);
                            return;
                        }
                        AuctionService.inst().createAuction(
                                player, hand, startPrice, buyNowPrice, 0,
                                durationMinutes, autoExtend, success -> {
                                    if (success) {
                                        plugin.getScheduler().closeInventory(player);
                                        GuiAuctionMain.open(player);
                                    }
                                });
                        break;
                    }
                    case '返': {
                        GuiAuctionMain.open(player);
                        break;
                    }
                    default: {
                        LoadedIcon icon = otherIcons.get(id);
                        if (icon != null) {
                            icon.click(player, type);
                        }
                        break;
                    }
                }
            });
        }

        private void refreshState() {
            doSearch(); // 重建预览（价格/时长变化同步到展示拍卖）
            updateReplacements();
            updateInventory(getInventory());
            top.mrxiaom.pluginbase.utils.Util.submitInvUpdate(player);
            actionLock = false;
        }

        private Double parseDouble(String input) {
            try {
                return Double.parseDouble(input.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
