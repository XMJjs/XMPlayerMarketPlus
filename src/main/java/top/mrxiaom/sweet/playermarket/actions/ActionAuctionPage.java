package top.mrxiaom.sweet.playermarket.actions;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import top.mrxiaom.pluginbase.api.IAction;
import top.mrxiaom.pluginbase.api.IActionProvider;
import top.mrxiaom.pluginbase.func.GuiManager;
import top.mrxiaom.pluginbase.gui.IGuiHolder;
import top.mrxiaom.sweet.playermarket.gui.api.IGuiPageable;

import java.util.List;

/**
 * 拍卖列表翻页动作：{@code [auction-page]+1} / {@code [auction-page]-1}。
 * 对所有拍卖分页 GUI（浏览/我的拍卖/竞拍记录）生效（走 IGuiPageable 接口）。
 */
public class ActionAuctionPage implements IAction {
    public static final IActionProvider PROVIDER = (input) -> {
        if (input instanceof ConfigurationSection) {
            ConfigurationSection section = (ConfigurationSection) input;
            if ("auction-page".equals(section.getString("type"))) {
                return new ActionAuctionPage(section.getInt("pages", 1));
            }
        } else {
            String s = String.valueOf(input);
            if (s.startsWith("[auction-page]")) {
                String op = s.substring("[auction-page]".length());
                if ("+1".equals(op)) return new ActionAuctionPage(1);
                if ("-1".equals(op)) return new ActionAuctionPage(-1);
                if ("+5".equals(op)) return new ActionAuctionPage(5);
                if ("-5".equals(op)) return new ActionAuctionPage(-5);
            }
        }
        return null;
    };

    private final int pages;

    public ActionAuctionPage(int pages) {
        this.pages = pages;
    }

    @Override
    public void run(@NotNull Player player, List<top.mrxiaom.pluginbase.utils.Pair<String, Object>> replacements) {
        IGuiHolder gui = GuiManager.inst().getOpeningGui(player);
        if (gui instanceof IGuiPageable) {
            IGuiPageable pageable = (IGuiPageable) gui;
            if (pages > 0) {
                pageable.turnPageDown(pages);
            } else {
                pageable.turnPageUp(-pages);
            }
        }
    }
}
