package top.mrxiaom.sweet.playermarket.gui.auction;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.jetbrains.annotations.NotNull;
import top.mrxiaom.pluginbase.api.InventoryViewAccessor;
import top.mrxiaom.pluginbase.gui.IGuiHolder;
import top.mrxiaom.sweet.playermarket.SweetPlayerMarket;
import top.mrxiaom.sweet.playermarket.auction.Auction;
import top.mrxiaom.sweet.playermarket.auction.AuctionService;

import java.util.List;

/**
 * 我的拍卖界面（卖家视角）。
 * 打开入口：主菜单"我的拍卖"、/spm auction my。
 *
 * <p>交互：
 * <ul>
 *   <li>左键：打开详情（可取消/查看）</li>
 *   <li>右键：领取待领款项/物品（claim.seller.*）</li>
 *   <li>Shift+左键：直接取消（仅 ACTIVE）</li>
 * </ul>
 */
public class GuiAuctionMy extends AbstractAuctionGui {
    public GuiAuctionMy(SweetPlayerMarket plugin) {
        super(plugin, "auction-my.yml");
    }

    public static GuiAuctionMy inst() {
        return instanceOf(GuiAuctionMy.class);
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
                        .countBySeller(conn, plugin.getKey(player));
            } catch (java.sql.SQLException e) {
                this.totalCount = 0;
            }
            List<Auction> fetched = AuctionService.inst().getMyAuctions(player, page, slotsSize);
            this.auctions.clear();
            this.auctions.addAll(fetched);
        }

        @Override
        protected List<Auction> fetchPage(int page, int size) {
            return AuctionService.inst().getMyAuctions(player, page, size);
        }

        @Override
        protected void onClickAuction(InventoryAction action, ClickType click,
                                      InventoryType.SlotType slotType, int slot,
                                      Auction auction, int index,
                                      InventoryViewAccessor view, InventoryClickEvent event) {
            if (click == ClickType.SHIFT_LEFT) {
                // 直接取消
                AuctionService.inst().cancelAuction(player, auction.auctionId(), this::afterAction);
                return;
            }
            if (click == ClickType.RIGHT) {
                // 领取待领款项/物品
                AuctionService.inst().claim(player, auction.auctionId(), true, this::afterAction);
                return;
            }
            GuiAuctionMy.Impl parent = GuiAuctionMy.this.new Impl(player);
            parent.page = this.page;
            GuiAuctionDetail.open(player, auction.auctionId(), parent);
        }

        private void afterAction(boolean success) {
            if (success) {
                plugin.getScheduler().runTask(() -> {
                    GuiAuctionMy.Impl refreshed = GuiAuctionMy.this.new Impl(player);
                    refreshed.page = this.page;
                    refreshed.open();
                });
            }
        }
    }
}
