package top.mrxiaom.sweet.playermarket.actions;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import top.mrxiaom.pluginbase.api.IAction;
import top.mrxiaom.pluginbase.api.IActionProvider;
import top.mrxiaom.pluginbase.func.GuiManager;
import top.mrxiaom.pluginbase.gui.IGuiHolder;
import top.mrxiaom.sweet.playermarket.gui.auction.GuiAuctionList;

import java.util.List;

/**
 * 拍卖排序切换动作：{@code [auction-sort]}。
 * 循环切换 time_left → price_asc → price_desc → newest。
 */
public class ActionAuctionSort implements IAction {
    private static final String[] SORTS = {"time_left", "price_asc", "price_desc", "newest"};

    public static final IActionProvider PROVIDER = (input) -> {
        if (input instanceof ConfigurationSection) {
            ConfigurationSection section = (ConfigurationSection) input;
            if ("auction-sort".equals(section.getString("type"))) {
                return new ActionAuctionSort();
            }
        } else {
            String s = String.valueOf(input);
            if (s.equals("[auction-sort]") || s.equals("auction-sort")) {
                return new ActionAuctionSort();
            }
        }
        return null;
    };

    @Override
    public void run(@NotNull Player player, List<top.mrxiaom.pluginbase.utils.Pair<String, Object>> replacements) {
        IGuiHolder gui = GuiManager.inst().getOpeningGui(player);
        if (gui instanceof GuiAuctionList.Impl) {
            GuiAuctionList.Impl impl = (GuiAuctionList.Impl) gui;
            String current = impl.getSort();
            int index = 0;
            for (int i = 0; i < SORTS.length; i++) {
                if (SORTS[i].equals(current)) {
                    index = i;
                    break;
                }
            }
            GuiAuctionList.open(player, SORTS[(index + 1) % SORTS.length]);
        }
    }
}
