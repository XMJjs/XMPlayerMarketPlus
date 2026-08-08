package top.mrxiaom.sweet.playermarket.auction;

import top.mrxiaom.pluginbase.func.language.Language;
import top.mrxiaom.pluginbase.func.language.Message;

import static top.mrxiaom.pluginbase.func.language.LanguageFieldAutoHolder.field;

/**
 * 拍卖模块消息（messages.yml → messages.auction.*），
 * 与 Messages.java 同模式，由 LanguageManager 自动映射。
 *
 * <p>接入点：在 SweetPlayerMarket.beforeEnable 的 LanguageManager 链中追加
 * {@code .register(AuctionMessages.class)}。
 */
@Language(prefix = "messages.auction.")
public class AuctionMessages {

    public static final Message create__no_item = field("&e请手持要拍卖的物品");
    public static final Message create__no_price_valid = field("&e请输入正确的起拍价");
    public static final Message create__price_too_low = field("&e起拍价不能低于 %min%");
    public static final Message create__duration_invalid = field("&e拍卖时长无效，格式示例: 1h / 24h / 7d");
    public static final Message create__duration_out_of_range = field("&e拍卖时长必须在 %min% ~ %max% 之间");
    public static final Message create__limit_reached = field("&e你的同时拍卖数已达上限 (%limit%)");
    public static final Message create__no_enough_items = field("&e你没有足够的物品");
    public static final Message create__listing_fee_failed = field("&e上架手续费不足，需要 %fee%");
    public static final Message create__success = field("&a拍卖已创建: <item>%item%</item> 起拍价 &e%price%");
    public static final Message create__failed = field("&e创建拍卖失败，请联系管理员");

    public static final Message bid__cannot_own = field("&e你不能出价自己的拍卖");
    public static final Message bid__prompt = field("&e请在聊天框输入你的出价金额（输入 cancel 取消）");
    public static final Message bid__too_low = field("&e出价必须不低于 %amount%");
    public static final Message bid__not_enough = field("&e你的余额不足，需要 %amount%");
    public static final Message bid__success = field("&a出价成功: &e%amount% &7(最高出价)");
    public static final Message bid__outbid = field("&e你在拍卖 %item% 的出价已被超越，最高价为 &b%amount%");
    public static final Message bid__extend_notice = field("&a拍卖已自动顺延 %minutes% 分钟");
    public static final Message bid__fail = field("&e出价失败，拍卖可能已结束或被取消");

    public static final Message buy_now__cannot_own = field("&e你不能购买自己的拍卖");
    public static final Message buy_now__not_enough = field("&e你的余额不足，需要 %amount%");
    public static final Message buy_now__success = field("&a你已用 &e%amount% &a拍下 <item>%item%</item>");
    public static final Message buy_now__fail = field("&e一口价购买失败，拍卖可能已结束");

    public static final Message cancel__not_own = field("&e这不是你的拍卖");
    public static final Message cancel__not_active = field("&e该拍卖已结束，无法取消");
    public static final Message cancel__success = field("&a拍卖已取消，物品已退还（背包满时进入待领区）");
    public static final Message cancel__fail = field("&e取消拍卖失败");

    public static final Message expired__seller_notice = field("&e你的拍卖 <item>%item%</item> 已流拍，物品已退还");
    public static final Message sold__seller_notice = field("&a你的拍卖 <item>%item%</item> 以 &e%price% &a成交!");
    public static final Message sold__bidder_win = field("&a你赢得了拍卖 <item>%item%</item>，出价 &e%price%");
    public static final Message sold__bidder_lose = field("&e你在拍卖 <item>%item%</item> 中的出价已退还");

    public static final Message claim__nothing = field("&e没有可领取的内容");
    public static final Message claim__item_success = field("&a已领取物品 <item>%item%</item> ×%count%");
    public static final Message claim__money_success = field("&a已领取 &e%amount%");
    public static final Message claim__inventory_full = field("&e背包已满，请清理后重试");
    public static final Message claim__fail = field("&e领取失败");

    public static final Message token__given = field("&a已获得拍卖令牌，右键打开拍卖行");
    public static final Message no_permission = field("&c你没有执行该操作的权限");
}
