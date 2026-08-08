package top.mrxiaom.sweet.playermarket.auction;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.mrxiaom.sweet.playermarket.SweetPlayerMarket;
import top.mrxiaom.sweet.playermarket.func.ItemSerializerManager;

import java.time.LocalDateTime;

/**
 * 拍卖数据模型。
 *
 * <p>设计沿袭 PlayerAuctions 的 <b>不可变 + 乐观锁版本号</b> 思路：
 * 每次状态/价格变更都通过 {@code withXxx} 生成新实例，并让 version 自增；
 * 写入时执行 CAS（compare-and-set），版本不匹配即表示并发冲突，操作失败回滚。
 *
 * <p>相比 PlayerAuctions 的 record，本类补齐了竞拍必需字段：
 * 当前最高出价、最高出价者、加价幅度、自动延期开关与顺延时长。
 */
public final class Auction {
    private final @NotNull String auctionId;
    private final @NotNull String sellerId;
    private final @NotNull String sellerName;
    private final @NotNull ItemStack item;

    private final double startPrice;
    private final double currentBid;
    private final @Nullable String highestBidderId;
    private final @Nullable String highestBidderName;
    private final double buyNowPrice;      // <= 0 表示未设置一口价
    private final double bidIncrement;     // <= 0 表示使用默认加价幅度

    private final @NotNull LocalDateTime createdAt;
    private final @NotNull LocalDateTime endAt;
    private final boolean autoExtend;
    private final long extendMinutes;      // 最后 N 分钟内出价 → 顺延 N 分钟

    private final @NotNull AuctionStatus status;
    private final int version;

    private final @NotNull ConfigurationSection params; // 待领款项/物品等扩展参数

    public Auction(
            @NotNull String auctionId,
            @NotNull String sellerId,
            @NotNull String sellerName,
            @NotNull ItemStack item,
            double startPrice,
            double currentBid,
            @Nullable String highestBidderId,
            @Nullable String highestBidderName,
            double buyNowPrice,
            double bidIncrement,
            @NotNull LocalDateTime createdAt,
            @NotNull LocalDateTime endAt,
            boolean autoExtend,
            long extendMinutes,
            @NotNull AuctionStatus status,
            int version,
            @NotNull ConfigurationSection params
    ) {
        this.auctionId = auctionId;
        this.sellerId = sellerId;
        this.sellerName = sellerName;
        this.item = item;
        this.startPrice = startPrice;
        this.currentBid = currentBid;
        this.highestBidderId = highestBidderId;
        this.highestBidderName = highestBidderName;
        this.buyNowPrice = buyNowPrice;
        this.bidIncrement = bidIncrement;
        this.createdAt = createdAt;
        this.endAt = endAt;
        this.autoExtend = autoExtend;
        this.extendMinutes = extendMinutes;
        this.status = status;
        this.version = version;
        this.params = params;
    }

    // ─────────────────────────── 读取器 ───────────────────────────

    public @NotNull String auctionId() { return auctionId; }
    public @NotNull String sellerId() { return sellerId; }
    public @NotNull String sellerName() { return sellerName; }
    public @NotNull ItemStack item() { return item.clone(); }
    public double startPrice() { return startPrice; }
    public double currentBid() { return currentBid; }
    public @Nullable String highestBidderId() { return highestBidderId; }
    public @Nullable String highestBidderName() { return highestBidderName; }
    public double buyNowPrice() { return buyNowPrice; }
    public double bidIncrement() { return bidIncrement; }
    public @NotNull LocalDateTime createdAt() { return createdAt; }
    public @NotNull LocalDateTime endAt() { return endAt; }
    public boolean autoExtend() { return autoExtend; }
    public long extendMinutes() { return extendMinutes; }
    public @NotNull AuctionStatus status() { return status; }
    public int version() { return version; }
    public @NotNull ConfigurationSection params() { return params; }

    public boolean isActive() { return status == AuctionStatus.ACTIVE; }
    public boolean isOwn(String playerId) { return sellerId.equals(playerId); }

    /** 是否已过期（endAt 早于当前时间） */
    public boolean isExpired(LocalDateTime now) {
        return now.isAfter(endAt);
    }

    /** 是否已进入自动延期的触发窗口（剩余时间 &lt;= triggerMinutes） */
    public boolean inExtendWindow(LocalDateTime now, long triggerMinutes) {
        if (!autoExtend || triggerMinutes <= 0) return false;
        return now.isAfter(endAt.minusMinutes(triggerMinutes));
    }

    /** 计算下一次合法出价金额（当前最高价 + 加价幅度；无人出价时为起拍价 + 加价幅度） */
    public double nextBidAmount() {
        double inc = bidIncrement > 0 ? bidIncrement : 1.0;
        double base = highestBidderId == null ? startPrice : currentBid;
        return base + inc;
    }

    // ─────────────────────────── 不可变更新 ───────────────────────────

    public Auction withStatus(@NotNull AuctionStatus newStatus) {
        return new Auction(auctionId, sellerId, sellerName, item, startPrice, currentBid,
                highestBidderId, highestBidderName, buyNowPrice, bidIncrement,
                createdAt, endAt, autoExtend, extendMinutes, newStatus, version + 1, params);
    }

    /** 出价成功后的新状态（可能触发自动延期） */
    public Auction withBid(String bidderId, String bidderName, double amount,
                           LocalDateTime newEndAt) {
        return new Auction(auctionId, sellerId, sellerName, item, startPrice, amount,
                bidderId, bidderName, buyNowPrice, bidIncrement,
                createdAt, newEndAt, autoExtend, extendMinutes, AuctionStatus.ACTIVE, version + 1, params);
    }

    public Auction withParams(@NotNull ConfigurationSection newParams) {
        return new Auction(auctionId, sellerId, sellerName, item, startPrice, currentBid,
                highestBidderId, highestBidderName, buyNowPrice, bidIncrement,
                createdAt, endAt, autoExtend, extendMinutes, status, version + 1, newParams);
    }

    // ─────────────────────────── 序列化 ───────────────────────────

    /**
     * 生成用于数据库 data 列的 YAML 配置。
     * 物品经 ItemSerializerManager（item-nbt-api Base64）序列化，跨版本稳定。
     */
    public @NotNull YamlConfiguration data() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("seller.name", sellerName);
        if (highestBidderName != null) {
            config.set("highest_bidder.name", highestBidderName);
        }
        ItemSerializerManager.inst().setItem(config, item);
        config.set("params", params);
        return config;
    }

    /**
     * 从数据库 data 列反序列化。
     * 注意：data 只含 seller.name / highest_bidder.name / item / params，其余字段由行内列（列式）提供。
     */
    public static Auction fromData(String auctionId, String sellerId,
                                   double startPrice, double currentBid,
                                   String highestBidderId, double buyNowPrice,
                                   double bidIncrement,
                                   LocalDateTime createdAt, LocalDateTime endAt,
                                   boolean autoExtend, long extendMinutes,
                                   AuctionStatus status, int version,
                                   @NotNull ConfigurationSection data) {
        ItemStack item = ItemSerializerManager.inst().getItem(data);
        String sellerName = data.getString("seller.name", sellerId);
        String highestBidderName = data.getString("highest_bidder.name");
        ConfigurationSection params = data.getConfigurationSection("params");
        if (params == null) params = new MemoryConfiguration();
        return new Auction(auctionId, sellerId, sellerName, item, startPrice, currentBid,
                highestBidderId, highestBidderName, buyNowPrice, bidIncrement,
                createdAt, endAt, autoExtend, extendMinutes, status, version, params);
    }

    /** 生成可用的构建入口（供测试/外部 API 使用） */
    public static AuctionBuilder builder(SweetPlayerMarket plugin, String sellerId, String sellerName) {
        return new AuctionBuilder(plugin, sellerId, sellerName);
    }

    public static final class AuctionBuilder {
        private final SweetPlayerMarket plugin;
        private final String sellerId;
        private final String sellerName;
        private ItemStack item;
        private double startPrice;
        private double buyNowPrice;
        private double bidIncrement;
        private LocalDateTime createdAt = LocalDateTime.now();
        private LocalDateTime endAt;
        private boolean autoExtend;
        private long extendMinutes;
        private ConfigurationSection params = new MemoryConfiguration();

        private AuctionBuilder(SweetPlayerMarket plugin, String sellerId, String sellerName) {
            this.plugin = plugin;
            this.sellerId = sellerId;
            this.sellerName = sellerName;
        }

        public AuctionBuilder item(ItemStack item) { this.item = item; return this; }
        public AuctionBuilder startPrice(double v) { this.startPrice = v; return this; }
        public AuctionBuilder buyNowPrice(double v) { this.buyNowPrice = v; return this; }
        public AuctionBuilder bidIncrement(double v) { this.bidIncrement = v; return this; }
        public AuctionBuilder endAt(LocalDateTime v) { this.endAt = v; return this; }
        public AuctionBuilder autoExtend(boolean v) { this.autoExtend = v; return this; }
        public AuctionBuilder extendMinutes(long v) { this.extendMinutes = v; return this; }
        public AuctionBuilder params(ConfigurationSection v) { this.params = v; return this; }

        public Auction build() {
            if (item == null) throw new IllegalStateException("item is required");
            if (endAt == null) throw new IllegalStateException("endAt is required");
            return new Auction(
                    java.util.UUID.randomUUID().toString(),
                    sellerId, sellerName, item, startPrice, 0,
                    null, null, buyNowPrice, bidIncrement,
                    createdAt, endAt, autoExtend, extendMinutes,
                    AuctionStatus.ACTIVE, 1, params);
        }
    }
}
