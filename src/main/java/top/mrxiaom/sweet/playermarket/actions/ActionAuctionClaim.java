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
 * 领取动作：{@code [auction-claim:seller]}（卖家领款）或
 * {@code [auction-claim:buyer]}（买家领物品）。
 * 对应 PlayerAuctions 邮箱领取语义：成交款/流拍物品/退款进待领区后在此领取。
 */
public class ActionAuctionClaim extends AbstractActionWithAuction {
    public static final IActionProvider PROVIDER = (input) -> {
        if (input instanceof ConfigurationSection) {
            ConfigurationSection section = (ConfigurationSection) input;
            if ("auction-claim".equals(section.getString("type"))) {
                return new ActionAuctionClaim("seller".equalsIgnoreCase(section.getString("side", "seller")));
            }
        } else {
            String s = String.valueOf(input);
            if (s.startsWith("[auction-claim:")) {
                String side = s.substring("[auction-claim:".length(), s.length() - 1);
                return new ActionAuctionClaim("buyer".equalsIgnoreCase(side));
            }
        }
        return null;
    };

    private final boolean buyerSide;

    public ActionAuctionClaim(boolean buyerSide) {
        this.buyerSide = buyerSide;
    }

    @Override
    public void run(@NotNull Player player, @NotNull Auction auction,
                    @NotNull List<top.mrxiaom.pluginbase.utils.Pair<String, Object>> replacements) {
        IGuiHolder gui = GuiManager.inst().getOpeningGui(player);
        AuctionService.inst().claim(player, auction.auctionId(), !buyerSide, success -> {
            if (success && gui instanceof IGuiRefreshable) {
                ((IGuiRefreshable) gui).refreshGui();
            }
        });
    }
}
