package top.mrxiaom.sweet.playermarket.actions;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import top.mrxiaom.pluginbase.api.IAction;
import top.mrxiaom.pluginbase.api.IActionProvider;
import top.mrxiaom.sweet.playermarket.gui.auction.GuiAuctionBids;
import top.mrxiaom.sweet.playermarket.gui.auction.GuiAuctionCreate;
import top.mrxiaom.sweet.playermarket.gui.auction.GuiAuctionList;
import top.mrxiaom.sweet.playermarket.gui.auction.GuiAuctionMain;
import top.mrxiaom.sweet.playermarket.gui.auction.GuiAuctionMy;

/**
 * 打开拍卖 GUI 的动作：{@code [auction-open:main|list|my|bids|create]}。
 * 用于主菜单按钮、市场 GUI 拍卖按钮、拍卖令牌/NPC 挂钩等入口。
 */
public class ActionAuctionOpen implements IAction {
    public static final ActionAuctionOpen INSTANCE = new ActionAuctionOpen();
    public static final IActionProvider PROVIDER = (input) -> {
        if (input instanceof ConfigurationSection) {
            ConfigurationSection section = (ConfigurationSection) input;
            if ("auction-open".equals(section.getString("type"))) {
                return new ActionAuctionOpen(section.getString("target", "main"));
            }
        } else {
            String s = String.valueOf(input);
            if (s.startsWith("[auction-open:")) {
                String target = s.substring("[auction-open:".length(), s.length() - 1);
                return new ActionAuctionOpen(target);
            }
        }
        return null;
    };

    private final String target;

    public ActionAuctionOpen() {
        this("main");
    }

    public ActionAuctionOpen(String target) {
        this.target = target;
    }

    @Override
    public void run(@NotNull Player player, java.util.List<top.mrxiaom.pluginbase.utils.Pair<String, Object>> replacements) {
        switch (target) {
            case "list":
                GuiAuctionList.open(player);
                break;
            case "my":
                GuiAuctionMy.open(player);
                break;
            case "bids":
                GuiAuctionBids.open(player);
                break;
            case "create":
                GuiAuctionCreate.open(player);
                break;
            case "main":
            default:
                GuiAuctionMain.open(player);
                break;
        }
    }
}
