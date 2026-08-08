package top.mrxiaom.sweet.playermarket.gui.auction;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.jetbrains.annotations.Nullable;
import top.mrxiaom.pluginbase.api.InventoryViewAccessor;
import top.mrxiaom.pluginbase.func.AutoRegister;
import top.mrxiaom.pluginbase.func.gui.LoadedIcon;
import top.mrxiaom.pluginbase.gui.IGuiHolder;
import top.mrxiaom.pluginbase.utils.Pair;
import top.mrxiaom.sweet.playermarket.SweetPlayerMarket;
import top.mrxiaom.sweet.playermarket.auction.Auction;
import top.mrxiaom.sweet.playermarket.auction.AuctionMessages;
import top.mrxiaom.sweet.playermarket.auction.AuctionService;
import top.mrxiaom.sweet.playermarket.utils.Prompter;

import java.util.Collections;
import java.util.List;

/**
 * 拍卖详情界面：展示单个拍卖 + 出价 / 一口价 / 取消 / 返回。
 *
 * <p>按钮交互在代码中直接驱动 AuctionService（YAML 仅负责外观）：
 * <ul>
 *   <li>'出'：左键按最低加价出价；右键聊天输入自定义出价</li>
 *   <li>'口'：一口价购买（Shift 左键）</li>
 *   <li>'取'：取消拍卖（卖家）</li>
 *   <li>'返'：返回上一界面</li>
 * </ul>
 */
@AutoRegister
public class GuiAuctionDetail extends AbstractAuctionGui {
    public GuiAuctionDetail(SweetPlayerMarket plugin) {
        super(plugin, "auction-detail.yml");
    }

    public static GuiAuctionDetail inst() {
        return instanceOf(GuiAuctionDetail.class);
    }

    public static void open(Player player, String auctionId, @Nullable IGuiHolder parent) {
        inst().new Impl(player, auctionId, parent).open();
    }

    public class Impl extends AuctionGui {
        private final String auctionId;
        private final IGuiHolder parent;
        private Auction current;

        protected Impl(Player player, String auctionId, IGuiHolder parent) {
            super(player);
            this.auctionId = auctionId;
            this.parent = parent;
            postInit();
        }

        public String auctionId() {
            return auctionId;
        }

        @Override
        protected void doSearch() {
            Auction auction = AuctionService.inst().getAuction(auctionId);
            this.current = auction;
            this.totalCount = 1;
            this.auctions.clear();
            if (auction != null) {
                this.auctions.add(auction);
            }
        }

        @Override
        protected List<Auction> fetchPage(int page, int size) {
            Auction auction = AuctionService.inst().getAuction(auctionId);
            return auction == null ? Collections.emptyList() : Collections.singletonList(auction);
        }

        @Override
        protected void onClickAuction(InventoryAction action, ClickType click,
                                      InventoryType.SlotType slotType, int slot,
                                      Auction auction, int index,
                                      InventoryViewAccessor view, InventoryClickEvent event) {
            // 物品本身无操作（提示信息展示）
        }

        @Override
        public void handleOtherClick(ClickType type, Character id) {
            if (id == null) {
                actionLock = false;
                return;
            }
            plugin.getScheduler().runTask(() -> {
                actionLock = false;
                Auction auction = AuctionService.inst().getAuction(auctionId);
                if (auction == null) {
                    AuctionMessages.bid__fail.tm(player);
                    return;
                }
                switch (id) {
                    case '出': { // 出价
                        if (type.isShiftClick()) {
                            // Shift：聊天输入自定义出价
                            AuctionMessages.bid__prompt.tm(player);
                            Prompter.chat(player, input -> {
                                Double amount = parseDouble(input);
                                if (amount == null) {
                                    AuctionMessages.bid__too_low.tm(player, Pair.of("%amount%", "0"));
                                    return;
                                }
                                AuctionService.inst().bid(player, auctionId, amount, this::afterBid);
                            }, null);
                        } else {
                            AuctionService.inst().bid(player, auctionId, auction.nextBidAmount(), this::afterBid);
                        }
                        break;
                    }
                    case '口': { // 一口价
                        AuctionService.inst().buyNow(player, auctionId, this::afterBid);
                        break;
                    }
                    case '取': { // 取消
                        AuctionService.inst().cancelAuction(player, auctionId, this::afterBid);
                        break;
                    }
                    case '返': { // 返回
                        if (parent != null) {
                            parent.open();
                            if (parent instanceof top.mrxiaom.pluginbase.gui.IGuiRefreshable) {
                                // 返回后刷新列表数据（出价/取消后保持最新）
                                ((top.mrxiaom.pluginbase.gui.IGuiRefreshable) parent).refreshGui();
                            }
                        } else {
                            GuiAuctionMain.open(player);
                        }
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

        private void afterBid(boolean success) {
            if (success) {
                plugin.getScheduler().runTask(GuiAuctionDetail.this::refreshFromCurrent);
            }
        }

        private void refreshFromCurrent() {
            GuiAuctionDetail.Impl impl = GuiAuctionDetail.this.new Impl(player, auctionId, parent);
            impl.open();
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
