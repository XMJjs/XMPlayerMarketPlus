package top.mrxiaom.sweet.playermarket.actions;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.mrxiaom.pluginbase.api.IAction;
import top.mrxiaom.pluginbase.utils.Pair;
import top.mrxiaom.sweet.playermarket.SweetPlayerMarket;
import top.mrxiaom.sweet.playermarket.auction.Auction;
import top.mrxiaom.sweet.playermarket.utils.Utils;

import java.util.ArrayList;
import java.util.List;

/**
 * 携带拍卖上下文的动作基类（仿 AbstractActionWithMarketItem）。
 * 动作参数经 __internal__auction 获取当前拍卖实例。
 *
 * <p>run 方法统一 try-catch：任何运行时异常都记录日志而不向上抛，
 * 防止父 GUI 的 actionLock 无法复位导致界面点击卡死。
 */
public abstract class AbstractActionWithAuction implements IAction {
    @Override
    public void run(@NotNull Player player, @Nullable List<Pair<String, Object>> replacements) {
        try {
            Object obj = Utils.get(replacements, "__internal__auction");
            if (obj instanceof Auction) {
                run(player, (Auction) obj, replacements == null ? new ArrayList<>() : replacements);
            }
        } catch (Throwable t) {
            SweetPlayerMarket.getInstance().warn("拍卖动作执行异常: " + t.getMessage(), t);
        }
    }

    public abstract void run(@NotNull Player player, @NotNull Auction auction,
                             @NotNull List<Pair<String, Object>> replacements);
}
