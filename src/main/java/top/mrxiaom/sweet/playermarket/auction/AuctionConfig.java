package top.mrxiaom.sweet.playermarket.auction;

import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;
import top.mrxiaom.pluginbase.utils.ConfigUtils;
import top.mrxiaom.sweet.playermarket.SweetPlayerMarket;
import top.mrxiaom.sweet.playermarket.func.AbstractModule;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * auction.yml 配置映射（时长解析移植自 PlayerAuctions DurationParser）。
 */
public class AuctionConfig extends AbstractModule {
    private static final Pattern DURATION = Pattern.compile("(\\d+)([smhdw])");

    private boolean enabled = true;
    private String currency = "Vault";
    private int maxAuctionsPerPlayer = 5;
    private long defaultDurationMinutes = 24 * 60;
    private long minDurationMinutes = 60;
    private long maxDurationMinutes = 7 * 24 * 60;
    private double minStartPrice = 1.0;
    private double bidIncrement = 1.0;
    private double minBidIncrement = 1.0;
    private boolean bidPayImmediately = true;
    private long expireIntervalSeconds = 30;

    private boolean autoExtendEnabled = true;
    private long extendTriggerMinutes = 5;
    private long extendMinutes = 5;

    private boolean taxEnabled = true;
    private double taxPercent = 5.0;

    private boolean listingFeeEnabled = false;
    private double listingFee = 0.0;

    private boolean broadcastCreate = true;
    private boolean broadcastSell = true;
    private boolean broadcastBuyNow = true;
    private String broadcastRange = "GLOBAL";

    private long mailboxRetentionDays = 30;

    private boolean entranceToken = true;
    private boolean entranceNpc = true;
    private String tokenMaterial = "PAPER";

    public AuctionConfig(SweetPlayerMarket plugin) {
        super(plugin);
    }

    public static AuctionConfig inst() {
        return instanceOf(AuctionConfig.class);
    }

    @Override
    public int priority() {
        return 990; // 先于 AuctionExpireTask / AuctionService 等依赖方加载
    }

    @Override
    public void reloadConfig(MemoryConfiguration config) {
        // 拍卖配置独立存放于 auction.yml（与 config.yml 分离），此处手动加载
        File file = plugin.resolve("./auction.yml");
        if (!file.exists()) {
            plugin.saveResource("auction.yml", file);
        }
        FileConfiguration cfg = plugin.resolveGotoFlag(ConfigUtils.load(file));

        enabled = cfg.getBoolean("auction.enabled", true);
        currency = cfg.getString("auction.currency", "Vault");
        maxAuctionsPerPlayer = cfg.getInt("auction.max-auctions-per-player", 5);
        defaultDurationMinutes = parseMinutes(cfg.getString("auction.default-duration", "24h"), 24 * 60);
        minDurationMinutes = parseMinutes(cfg.getString("auction.min-duration", "1h"), 60);
        maxDurationMinutes = parseMinutes(cfg.getString("auction.max-duration", "168h"), 7 * 24 * 60);
        minStartPrice = cfg.getDouble("auction.min-start-price", 1.0);
        bidIncrement = cfg.getDouble("auction.bid-increment", 1.0);
        minBidIncrement = cfg.getDouble("auction.min-bid-increment", 1.0);
        bidPayImmediately = cfg.getBoolean("auction.bid-pay-immediately", true);
        expireIntervalSeconds = cfg.getLong("auction.expire-interval-seconds", 30);

        autoExtendEnabled = cfg.getBoolean("auction.auto-extend.enabled", true);
        extendTriggerMinutes = parseMinutes(cfg.getString("auction.auto-extend.trigger-minutes", "5m"), 5);
        extendMinutes = parseMinutes(cfg.getString("auction.auto-extend.extend-minutes", "5m"), 5);

        taxEnabled = cfg.getBoolean("auction.tax.enabled", true);
        taxPercent = cfg.getDouble("auction.tax.percent", 5.0);

        listingFeeEnabled = cfg.getBoolean("auction.listing-fee.enabled", false);
        listingFee = cfg.getDouble("auction.listing-fee.amount", 0.0);

        broadcastCreate = cfg.getBoolean("auction.broadcast.on-create", true);
        broadcastSell = cfg.getBoolean("auction.broadcast.on-sell", true);
        broadcastBuyNow = cfg.getBoolean("auction.broadcast.on-buy-now", true);
        broadcastRange = cfg.getString("auction.broadcast.range", "GLOBAL");

        mailboxRetentionDays = cfg.getLong("auction.mailbox.retention-days", 30);

        entranceToken = cfg.getBoolean("auction.entrances.auction-token", true);
        entranceNpc = cfg.getBoolean("auction.entrances.npc", true);
        tokenMaterial = cfg.getString("auction.entrances.token-material", "PAPER");
    }

    public boolean enabled() { return enabled; }
    public String currency() { return currency; }
    public int maxAuctionsPerPlayer() { return maxAuctionsPerPlayer; }
    public long defaultDurationMinutes() { return defaultDurationMinutes; }
    public long minDurationMinutes() { return minDurationMinutes; }
    public long maxDurationMinutes() { return maxDurationMinutes; }
    public double minStartPrice() { return minStartPrice; }
    public double bidIncrement() { return bidIncrement; }
    public double minBidIncrement() { return minBidIncrement; }
    public boolean bidPayImmediately() { return bidPayImmediately; }
    public long expireIntervalSeconds() { return expireIntervalSeconds; }
    public boolean autoExtendEnabled() { return autoExtendEnabled; }
    public long extendTriggerMinutes() { return extendTriggerMinutes; }
    public long extendMinutes() { return extendMinutes; }
    public boolean taxEnabled() { return taxEnabled; }
    public double taxPercent() { return taxPercent; }
    public boolean listingFeeEnabled() { return listingFeeEnabled; }
    public double listingFee() { return listingFee; }
    public boolean broadcastCreate() { return broadcastCreate; }
    public boolean broadcastSell() { return broadcastSell; }
    public boolean broadcastBuyNow() { return broadcastBuyNow; }
    public String broadcastRange() { return broadcastRange; }
    public long mailboxRetentionDays() { return mailboxRetentionDays; }
    public boolean entranceToken() { return entranceToken; }
    public boolean entranceNpc() { return entranceNpc; }
    public String tokenMaterial() { return tokenMaterial; }

    /**
     * 解析时长字符串（如 30s / 5m / 24h / 7d / 2w）为分钟数。
     * 无法解析时返回 fallback。
     */
    public static long parseMinutes(@NotNull String str, long fallback) {
        if (str == null || str.isBlank()) return fallback;
        Matcher matcher = DURATION.matcher(str.toLowerCase());
        long total = 0;
        boolean found = false;
        while (matcher.find()) {
            found = true;
            try {
                long value = Long.parseLong(matcher.group(1));
                char unit = matcher.group(2).charAt(0);
                switch (unit) {
                    case 's': total += value / 60L; break;
                    case 'm': total += value; break;
                    case 'h': total += value * 60L; break;
                    case 'd': total += value * 24L * 60L; break;
                    case 'w': total += value * 7L * 24L * 60L; break;
                    default: return fallback;
                }
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return found && total > 0 ? total : fallback;
    }
}
