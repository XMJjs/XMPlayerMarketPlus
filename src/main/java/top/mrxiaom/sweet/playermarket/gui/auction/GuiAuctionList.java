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
 * 拍卖浏览列表（分页 + 排序）。
 * 打开入口：主菜单"浏览拍卖"、/spm auction list。
 */
public class GuiAuctionList extends AbstractAuctionGui {
    public GuiAuctionList(SweetPlayerMarket plugin) {
        super(plugin, "auction-list.yml");
    }

    public static GuiAuctionList inst() {
        return instanceOf(GuiAuctionList.class);
    }

    public static void open(Player player) {
        open(player, "time_left");
    }

    public static void open(Player player, String sort) {
        inst().new Impl(player, sort).open();
    }

    public class Impl extends AuctionGui {
        protected Impl(Player player, String sort) {
            super(player);
            this.sort = sort;
            postInit();
        }

        @Override
        protected void doSearch() {
            this.totalCount = AuctionService.inst().countActiveAuctions();
            List<Auction> fetched = AuctionService.inst().getActiveAuctions(page, slotsSize, sort);
            this.auctions.clear();
            this.auctions.addAll(fetched);
        }

        @Override
        protected List<Auction> fetchPage(int page, int size) {
            return AuctionService.inst().getActiveAuctions(page, size, sort);
        }

        @Override
        protected void onClickAuction(InventoryAction action, ClickType click,
                                      InventoryType.SlotType slotType, int slot,
                                      Auction auction, int index,
                                      InventoryViewAccessor view, InventoryClickEvent event) {
            // 左键：打开详情（详情中可出价/一口价/取消）
            // 传入当前 page，保证从详情"返回"后仍停留在原页码
            GuiAuctionList.Impl parent = GuiAuctionList.this.new Impl(player, sort);
            parent.page = this.page;
            GuiAuctionDetail.open(player, auction.auctionId(), parent);
        }
    }
}
