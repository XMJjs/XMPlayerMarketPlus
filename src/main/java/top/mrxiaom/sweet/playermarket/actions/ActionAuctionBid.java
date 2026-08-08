package top.mrxiaom.sweet.playermarket.actions;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import top.mrxiaom.pluginbase.api.IActionProvider;
import top.mrxiaom.pluginbase.func.GuiManager;
import top.mrxiaom.pluginbase.gui.IGuiHolder;
import top.mrxiaom.pluginbase.gui.IGuiRefreshable;
import top.mrxiaom.sweet.playermarket.auction.Auction;
import top.mrxiaom.sweet.playermarket.auction.AuctionService;

import java.util.List;

/**
 * 出价动作：{@code [auction-bid]}（按最低加价出价）或
 * {@code [auction-bid:金额]}（指定金额）。
 * 从拍卖上下文中获取目标拍卖。
 */
public class ActionAuctionBid extends AbstractActionWithAuction {
    public static final ActionAuctionBid INSTANCE = new ActionAuctionBid();
    public static final IActionProvider PROVIDER = (input) -> {
        if (input instanceof ConfigurationSection) {
            ConfigurationSection section = (ConfigurationSection) input;
            if ("auction-bid".equals(section.getString("type"))) {
                return new ActionAuctionBid(section.contains("amount") ? section.getDouble("amount") : -1);
            }
        } else {
            String s = String.valueOf(input);
            if (s.startsWith("[auction-bid]")) {
                String rest = s.substring("[auction-bid]".length());
                if (rest.startsWith(":")) {
                    try {
                        return new ActionAuctionBid(Double.parseDouble(rest.substring(1)));
                    } catch (NumberFormatException e) {
                        return INSTANCE;
                    }
                }
                return INSTANCE;
            }
        }
        return null;
    };

    private final double fixedAmount;

    public ActionAuctionBid() {
        this(-1);
    }

    public ActionAuctionBid(double fixedAmount) {
        this.fixedAmount = fixedAmount;
    }

    @Override
    public void run(@NotNull Player player, @NotNull Auction auction,
                    @NotNull List<top.mrxiaom.pluginbase.utils.Pair<String, Object>> replacements) {
        double amount = fixedAmount > 0 ? fixedAmount : auction.nextBidAmount();
        IGuiHolder gui = GuiManager.inst().getOpeningGui(player);
        AuctionService.inst().bid(player, auction.auctionId(), amount, success -> {
            if (success && gui instanceof IGuiRefreshable) {
                ((IGuiRefreshable) gui).refreshGui();
            }
        });
    }
}
