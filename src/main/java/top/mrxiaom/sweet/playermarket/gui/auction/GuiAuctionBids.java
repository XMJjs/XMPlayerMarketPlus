package top.mrxiaom.sweet.playermarket.gui.auction;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.jetbrains.annotations.NotNull;
import top.mrxiaom.pluginbase.api.InventoryViewAccessor;
import top.mrxiaom.sweet.playermarket.SweetPlayerMarket;
import top.mrxiaom.sweet.playermarket.auction.Auction;
import top.mrxiaom.sweet.playermarket.auction.AuctionService;

import java.util.List;

/**
 * 竞拍记录界面（买家视角：我出价过/一口价买过的拍卖）。
 * 打开入口：主菜单"竞拍记录"、/spm auction bids。
 *
 * <p>交互：
 * <ul>
 *   <li>左键：打开详情（查看结果）</li>
 *   <li>右键：领取拍得的物品（claim.buyer.items，成交后背包满或离线场景）</li>
 * </ul>
 */
public class GuiAuctionBids extends AbstractAuctionGui {
    public GuiAuctionBids(SweetPlayerMarket plugin) {
        super(plugin, "auction-bids.yml");
    }

    public static GuiAuctionBids inst() {
        return instanceOf(GuiAuctionBids.class);
    }

    public static void open(Player player) {
        inst().new Impl(player).open();
    }

    public class Impl extends AuctionGui {
        protected Impl(Player player) {
            super(player);
            postInit();
        }

        @Override
        protected void doSearch() {
            try (java.sql.Connection conn = plugin.getConnection()) {
                this.totalCount = instanceOf(top.mrxiaom.sweet.playermarket.auction.AuctionDatabase.class)
                        .countByBidder(conn, plugin.getKey(player));
            } catch (java.sql.SQLException e) {
                this.totalCount = 0;
            }
            List<Auction> fetched = AuctionService.inst().getMyBids(player, page, slotsSize);
            this.auctions.clear();
            this.auctions.addAll(fetched);
        }

        @Override
        protected List<Auction> fetchPage(int page, int size) {
            return AuctionService.inst().getMyBids(player, page, size);
        }

        @Override
        protected void onClickAuction(InventoryAction action, ClickType click,
                                      InventoryType.SlotType slotType, int slot,
                                      Auction auction, int index,
                                      InventoryViewAccessor view, InventoryClickEvent event) {
            if (click == ClickType.RIGHT) {
                // 领取拍得的物品
                AuctionService.inst().claim(player, auction.auctionId(), false, this::afterAction);
                return;
            }
            GuiAuctionBids.Impl parent = GuiAuctionBids.this.new Impl(player);
            parent.page = this.page;
            GuiAuctionDetail.open(player, auction.auctionId(), parent);
        }

        private void afterAction(boolean success) {
            if (success) {
                plugin.getScheduler().runTask(() -> {
                    GuiAuctionBids.Impl refreshed = GuiAuctionBids.this.new Impl(player);
                    refreshed.page = this.page;
                    refreshed.open();
                });
            }
        }
    }
}
