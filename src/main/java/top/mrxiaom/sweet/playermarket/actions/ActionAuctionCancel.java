package top.mrxiaom.sweet.playermarket.actions;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import top.mrxiaom.pluginbase.api.IActionProvider;
import top.mrxiaom.pluginbase.func.GuiManager;
import top.mrxiaom.pluginbase.gui.IGuiHolder;
import top.mrxiaom.sweet.playermarket.gui.api.IGuiRefreshable;
import top.mrxiaom.sweet.playermarket.auction.Auction;
import top.mrxiaom.sweet.playermarket.auction.AuctionService;

import java.util.List;

/**
 * 取消拍卖动作：{@code [auction-cancel]}（仅卖家本人）。
 */
public class ActionAuctionCancel extends AbstractActionWithAuction {
    public static final ActionAuctionCancel INSTANCE = new ActionAuctionCancel();
    public static final IActionProvider PROVIDER = (input) -> {
        if (input instanceof ConfigurationSection) {
            ConfigurationSection section = (ConfigurationSection) input;
            if ("auction-cancel".equals(section.getString("type"))) {
                return INSTANCE;
            }
        } else {
            String s = String.valueOf(input);
            if (s.equals("[auction-cancel]") || s.equals("auction-cancel")) {
                return INSTANCE;
            }
        }
        return null;
    };

    @Override
    public void run(@NotNull Player player, @NotNull Auction auction,
                    @NotNull List<top.mrxiaom.pluginbase.utils.Pair<String, Object>> replacements) {
        IGuiHolder gui = GuiManager.inst().getOpeningGui(player);
        AuctionService.inst().cancelAuction(player, auction.auctionId(), success -> {
            if (success && gui instanceof IGuiRefreshable) {
                ((IGuiRefreshable) gui).refreshGui();
            }
        });
    }
}
