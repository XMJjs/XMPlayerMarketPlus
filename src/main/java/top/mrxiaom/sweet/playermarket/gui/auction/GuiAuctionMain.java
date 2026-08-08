package top.mrxiaom.sweet.playermarket.gui.auction;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import top.mrxiaom.pluginbase.api.InventoryViewAccessor;
import top.mrxiaom.pluginbase.gui.IGuiHolder;
import top.mrxiaom.sweet.playermarket.SweetPlayerMarket;
import top.mrxiaom.sweet.playermarket.auction.Auction;

import java.util.ArrayList;
import java.util.List;

/**
 * 拍卖主菜单（导航界面）。
 * 打开入口：/spm auction、拍卖令牌右键、NPC 右键、市场 GUI 按钮（[auction-open:main]）。
 *
 * <p>布局：gui/auction-main.yml，other-icons 提供
 * 浏览拍卖 / 我的拍卖 / 竞拍记录 / 创建拍卖 / 刷新 等导航按钮。
 */
public class GuiAuctionMain extends AbstractAuctionGui {
    public GuiAuctionMain(SweetPlayerMarket plugin) {
        super(plugin, "auction-main.yml");
    }

    public static GuiAuctionMain inst() {
        return instanceOf(GuiAuctionMain.class);
    }

    public static void open(Player player) {
        inst().new Impl(player).open();
    }

    /** 主菜单中央的 '拍' 槽：显示金块广告牌（iconItem），而非空槽图标 */
    @Override
    protected ItemStack applyMainIcon(top.mrxiaom.pluginbase.gui.IGuiHolder instance, Player player,
                                      char id, int index, int appearTimes) {
        if (id == '拍' && iconItem != null) {
            return iconItem.generateIcon(player);
        }
        return null;
    }

    public class Impl extends AuctionGui {
        protected Impl(Player player) {
            super(player);
            postInit();
        }

        @Override
        protected void doSearch() {
            this.totalCount = 0; // 主菜单无动态数据
        }

        @Override
        protected List<Auction> fetchPage(int page, int size) {
            return new ArrayList<>();
        }

        @Override
        protected void onClickAuction(InventoryAction action, ClickType click,
                                      InventoryType.SlotType slotType, int slot,
                                      Auction auction, int index,
                                      InventoryViewAccessor view, InventoryClickEvent event) {
            // 主菜单无拍卖槽，不会触发
        }
    }
}
