package top.mrxiaom.sweet.playermarket.commands.arguments;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import top.mrxiaom.pluginbase.utils.Pair;
import top.mrxiaom.sweet.playermarket.Messages;
import top.mrxiaom.sweet.playermarket.SweetPlayerMarket;
import top.mrxiaom.sweet.playermarket.api.AbstractArguments;
import top.mrxiaom.sweet.playermarket.auction.AuctionConfig;
import top.mrxiaom.sweet.playermarket.auction.AuctionMessages;
import top.mrxiaom.sweet.playermarket.auction.AuctionService;
import top.mrxiaom.sweet.playermarket.gui.auction.GuiAuctionBids;
import top.mrxiaom.sweet.playermarket.gui.auction.GuiAuctionCreate;
import top.mrxiaom.sweet.playermarket.gui.auction.GuiAuctionList;
import top.mrxiaom.sweet.playermarket.gui.auction.GuiAuctionMain;
import top.mrxiaom.sweet.playermarket.gui.auction.GuiAuctionMy;
import top.mrxiaom.sweet.playermarket.listener.AuctionInteractListener;

/**
 * /spm auction 子命令参数解析。
 *
 * <pre>
 * /spm auction                     → 拍卖主菜单
 * /spm auction list                → 拍卖浏览
 * /spm auction my                  → 我的拍卖
 * /spm auction bids                → 竞拍记录
 * /spm auction create              → 创建拍卖（GUI 向导）
 * /spm auction create &lt;价格&gt; [一口价] [时长] → 命令式创建（手持物品）
 * /spm auction sell &lt;价格&gt; [一口价] [时长] → 命令式快速上架（等价 PlayerAuctions /ah sell）
 * /spm auction cancel &lt;id&gt;        → 取消拍卖
 * /spm auction token               → 获取拍卖令牌（右键打开）
 * /spm auction reload              → 重载拍卖配置（op）
 * </pre>
 */
public class AuctionArguments extends AbstractArguments<CommandSender> {
    public AuctionArguments(CommandArguments parent) {
        super(parent);
    }

    public static AuctionArguments of(CommandArguments parent) {
        return new AuctionArguments(parent);
    }

    @Override
    public boolean execute(SweetPlayerMarket plugin, CommandSender sender) {
        if (match("list")) {
            if (!(sender instanceof Player)) return Messages.player__only.tm(sender);
            GuiAuctionList.open((Player) sender);
            return true;
        }
        if (match("my")) {
            if (!(sender instanceof Player)) return Messages.player__only.tm(sender);
            GuiAuctionMy.open((Player) sender);
            return true;
        }
        if (match("bids")) {
            if (!(sender instanceof Player)) return Messages.player__only.tm(sender);
            GuiAuctionBids.open((Player) sender);
            return true;
        }
        if (match("create")) {
            if (!(sender instanceof Player)) return Messages.player__only.tm(sender);
            Player player = (Player) sender;
            if (last()) {
                GuiAuctionCreate.open(player);
                return true;
            }
            return executeCreate(plugin, player);
        }
        if (match("sell")) {
            if (!(sender instanceof Player)) return Messages.player__only.tm(sender);
            return executeCreate(plugin, (Player) sender);
        }
        if (match("cancel")) {
            if (last()) return AuctionMessages.cancel__not_own.tm(sender);
            String auctionId = nextString();
            if (!(sender instanceof Player)) return Messages.player__only.tm(sender);
            AuctionService.inst().cancelAuction((Player) sender, auctionId, null);
            return true;
        }
        if (match("token")) {
            if (!(sender instanceof Player)) return Messages.player__only.tm(sender);
            AuctionInteractListener.giveToken((Player) sender);
            return true;
        }
        if (match("reload")) {
            if (!sender.isOp()) return AuctionMessages.no_permission.tm(sender);
            plugin.reloadConfig();
            return true;
        }
        // 默认：打开主菜单
        if (!(sender instanceof Player)) {
            sender.sendMessage("用法: /spm auction [list|my|bids|create|sell|cancel|token|reload]");
            return true;
        }
        GuiAuctionMain.open((Player) sender);
        return true;
    }

    private boolean executeCreate(SweetPlayerMarket plugin, Player player) {
        double startPrice = nextDouble(0, 0);
        if (startPrice <= 0) {
            return AuctionMessages.create__no_price_valid.tm(player);
        }
        double minPrice = AuctionConfig.inst().minStartPrice();
        if (startPrice < minPrice) {
            return AuctionMessages.create__price_too_low.tm(player, Pair.of("%min%", String.valueOf(minPrice)));
        }
        double buyNow = nextDouble(0, 0);
        long durationMinutes;
        if (last()) {
            durationMinutes = AuctionConfig.inst().defaultDurationMinutes();
        } else {
            durationMinutes = AuctionConfig.parseMinutes(nextString(), 0);
        }
        long minDuration = AuctionConfig.inst().minDurationMinutes();
        long maxDuration = AuctionConfig.inst().maxDurationMinutes();
        if (durationMinutes <= 0) {
            return AuctionMessages.create__duration_invalid.tm(player);
        }
        if (durationMinutes < minDuration || durationMinutes > maxDuration) {
            return AuctionMessages.create__duration_out_of_range.tm(player,
                    Pair.of("%min%", minDuration + "分钟"),
                    Pair.of("%max%", maxDuration + "分钟"));
        }
        AuctionService.inst().createAuction(
                player,
                player.getInventory().getItemInMainHand(),
                startPrice, buyNow, 0, durationMinutes,
                AuctionConfig.inst().autoExtendEnabled(), null);
        return true;
    }
}
