package top.mrxiaom.sweet.playermarket.auction;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.mrxiaom.sweet.playermarket.SweetPlayerMarket;
import top.mrxiaom.sweet.playermarket.economy.IEconomy;
import top.mrxiaom.sweet.playermarket.func.AbstractModule;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 拍卖核心业务服务。
 *
 * <p>完整移植 PlayerAuctions AuctionService 的三层并发防护，并补全竞拍能力：
 * <ul>
 *   <li><b>预占成交</b>：购买/成交先把状态置 FINISHED（version+1）再动资金；失败则 CAS 回滚 ACTIVE。</li>
 *   <li><b>乐观锁</b>：所有写操作经 {@link AuctionDatabase#updateAuctionIfVersionMatches} 做 CAS。</li>
 *   <li><b>实体线程切换</b>：背包/物品操作全部切回主线程（plugin.getScheduler().runTask）。</li>
 *   <li><b>待领补偿</b>：离线或背包满时，物品/款项写入拍卖 params（claim.*）供玩家后续领取。</li>
 * </ul>
 *
 * <p>经济：复用 SweetPlayerMarket 的 {@link IEconomy} 多币种体系（默认取 auction.yml 的 currency）。
 */
public class AuctionService extends AbstractModule {
    public AuctionService(SweetPlayerMarket plugin) {
        super(plugin);
    }

    public static AuctionService inst() {
        return instanceOf(AuctionService.class);
    }

    private AuctionDatabase db() {
        return instanceOf(AuctionDatabase.class);
    }

    private AuctionConfig config() {
        return instanceOf(AuctionConfig.class);
    }

    private @Nullable IEconomy economy() {
        return plugin.parseEconomy(config().currency());
    }

    // ═══════════════════════════════════════════════════════════════
    //  创建拍卖
    // ═══════════════════════════════════════════════════════════════

    /**
     * 创建拍卖。
     *
     * @param player        卖家（手持物品将被扣除）
     * @param startPrice    起拍价
     * @param buyNowPrice   一口价（&lt;=0 表示不设置）
     * @param bidIncrement  加价幅度（&lt;=0 使用默认）
     * @param durationMinutes 拍卖时长（分钟）
     * @param autoExtend    是否自动延期
     * @param callback      true=创建成功
     */
    public void createAuction(
            @NotNull Player player, @NotNull ItemStack item,
            double startPrice, double buyNowPrice, double bidIncrement,
            long durationMinutes, boolean autoExtend, Consumer<Boolean> callback
    ) {
        SweetPlayerMarket plugin = this.plugin;
        // 第一步：主线程扣除物品（背包操作必须主线程）
        plugin.getScheduler().runTask(() -> {
            if (!player.getInventory().containsAtLeast(item, item.getAmount())) {
                AuctionMessages.create__no_enough_items.tm(player);
                if (callback != null) callback.accept(false);
                return;
            }
            ItemStack toSell = item.clone();
            player.getInventory().removeItem(toSell);

            // 第二步：异步落库 + 手续费
            plugin.getScheduler().runTaskAsync(() -> {
                String key = plugin.getKey(player);
                double fee = config().listingFeeEnabled() ? config().listingFee() : 0;
                try (Connection conn = plugin.getConnection()) {
                    AuctionDatabase db = db();
                    // 上限校验
                    int mine = db.countBySeller(conn, key);
                    if (config().maxAuctionsPerPlayer() > 0 && mine >= config().maxAuctionsPerPlayer()) {
                        AuctionMessages.create__limit_reached.tm(player,
                                top.mrxiaom.pluginbase.utils.Pair.of("%limit%", config().maxAuctionsPerPlayer()));
                        rollbackItem(player, toSell);
                        if (callback != null) callback.accept(false);
                        return;
                    }
                    // 上架费
                    IEconomy currency = economy();
                    if (currency != null && fee > 0) {
                        if (!currency.has(player, fee) || !currency.takeMoney(player, fee)) {
                            AuctionMessages.create__listing_fee_failed.tm(player,
                                    top.mrxiaom.pluginbase.utils.Pair.of("%fee%", format(fee)));
                            rollbackItem(player, toSell);
                            if (callback != null) callback.accept(false);
                            return;
                        }
                    }
                    LocalDateTime endAt = LocalDateTime.now().plusMinutes(durationMinutes);
                    Auction auction = Auction.builder(plugin, key, player.getName())
                            .item(toSell)
                            .startPrice(startPrice)
                            .buyNowPrice(buyNowPrice)
                            .bidIncrement(bidIncrement > 0 ? bidIncrement : config().bidIncrement())
                            .endAt(endAt)
                            .autoExtend(autoExtend)
                            .extendMinutes(config().extendMinutes())
                            .build();
                    if (db.insertAuction(conn, auction)) {
                        AuctionMessages.create__success.tm(player,
                                top.mrxiaom.pluginbase.utils.Pair.of("%item%", display(item)),
                                top.mrxiaom.pluginbase.utils.Pair.of("%price%", format(startPrice)));
                        broadcast(config().broadcastCreate(),
                                "<#F5A623>⚒</#F5A623> <#ECF0F1>" + player.getName() + "</#ECF0F1>"
                                        + " <#7F8C8D>开启了拍卖</#7F8C8D> <#FFFFFF>" + display(item) + "</#FFFFFF>"
                                        + " <#7F8C8D>起拍价</#7F8C8D> <#2ECC71>" + format(startPrice) + "</#2ECC71>");
                        if (callback != null) callback.accept(true);
                    } else {
                        AuctionMessages.create__failed.tm(player);
                        rollbackItem(player, toSell);
                        if (callback != null) callback.accept(false);
                    }
                } catch (SQLException e) {
                    plugin.warn("创建拍卖失败: " + e.getMessage(), e);
                    AuctionMessages.create__failed.tm(player);
                    rollbackItem(player, toSell);
                    if (callback != null) callback.accept(false);
                }
            });
        });
    }

    // ═══════════════════════════════════════════════════════════════
    //  出价
    // ═══════════════════════════════════════════════════════════════

    /**
     * 对拍卖出价。
     *
     * @param amount 出价金额（必须 &gt;= 当前最高价 + 加价幅度）
     */
    public void bid(@NotNull Player player, @NotNull String auctionId, double amount, Consumer<Boolean> callback) {
        SweetPlayerMarket plugin = this.plugin;
        plugin.getScheduler().runTaskAsync(() -> {
            try (Connection conn = plugin.getConnection()) {
                AuctionDatabase db = db();
                Auction auction = db.getAuction(conn, auctionId);
                if (auction == null || !auction.isActive()) {
                    AuctionMessages.bid__fail.tm(player);
                    if (callback != null) callback.accept(false);
                    return;
                }
                String key = plugin.getKey(player);
                if (auction.isOwn(key)) {
                    AuctionMessages.bid__cannot_own.tm(player);
                    if (callback != null) callback.accept(false);
                    return;
                }
                double min = auction.nextBidAmount();
                if (amount < min) {
                    AuctionMessages.bid__too_low.tm(player,
                            top.mrxiaom.pluginbase.utils.Pair.of("%amount%", format(min)));
                    if (callback != null) callback.accept(false);
                    return;
                }
                IEconomy currency = economy();
                if (currency == null) {
                    if (callback != null) callback.accept(false);
                    return;
                }
                // 出价即冻结：先校验/扣新出价者款，CAS 更新成功后再退还旧最高出价者
                // （顺序保证：扣款失败或 CAS 冲突时旧出价者未被动过，资金一致）
                if (config().bidPayImmediately()) {
                    if (!currency.has(player, amount)) {
                        AuctionMessages.bid__not_enough.tm(player,
                                top.mrxiaom.pluginbase.utils.Pair.of("%amount%", format(amount)));
                        if (callback != null) callback.accept(false);
                        return;
                    }
                    if (!currency.takeMoney(player, amount)) {
                        AuctionMessages.bid__not_enough.tm(player,
                                top.mrxiaom.pluginbase.utils.Pair.of("%amount%", format(amount)));
                        if (callback != null) callback.accept(false);
                        return;
                    }
                } else {
                    if (!currency.has(player, amount)) {
                        AuctionMessages.bid__not_enough.tm(player,
                                top.mrxiaom.pluginbase.utils.Pair.of("%amount%", format(amount)));
                        if (callback != null) callback.accept(false);
                        return;
                    }
                }
                // 自动延期
                LocalDateTime now = LocalDateTime.now();
                LocalDateTime newEnd = auction.endAt();
                boolean extended = false;
                if (auction.autoExtend() && config().autoExtendEnabled()
                        && auction.inExtendWindow(now, config().extendTriggerMinutes())) {
                    newEnd = now.plusMinutes(config().extendMinutes());
                    extended = true;
                }
                Auction updated = auction.withBid(key, player.getName(), amount, newEnd)
                        .withParams(copyParamsWithBidCount(auction, 1));
                if (db.updateAuctionIfVersionMatches(conn, updated, auction.version())) {
                    // CAS 成功后才退款旧最高出价者（其押金解冻）
                    if (auction.highestBidderId() != null) {
                        refundBidder(conn, auction, "bid-refund");
                    }
                    db.putBid(conn, new AuctionBid(0, auctionId, key, player.getName(), amount, now, AuctionBid.BidType.BID));
                    // 通知被超越的出价者（仅出价即冻结模式下其钱已被退回）
                    if (auction.highestBidderId() != null) {
                        notifyPlayer(auction.highestBidderId(), AuctionMessages.bid__outbid,
                                top.mrxiaom.pluginbase.utils.Pair.of("%item%", display(auction.item())),
                                top.mrxiaom.pluginbase.utils.Pair.of("%amount%", format(amount)));
                    }
                    if (extended) {
                        AuctionMessages.bid__extend_notice.tm(player,
                                top.mrxiaom.pluginbase.utils.Pair.of("%minutes%", config().extendMinutes()));
                    }
                    AuctionMessages.bid__success.tm(player,
                            top.mrxiaom.pluginbase.utils.Pair.of("%amount%", format(amount)));
                    if (callback != null) callback.accept(true);
                } else {
                    // CAS 冲突：把钱退回
                    if (config().bidPayImmediately()) {
                        currency.giveMoney(player, amount);
                    }
                    AuctionMessages.bid__fail.tm(player);
                    if (callback != null) callback.accept(false);
                }
            } catch (SQLException e) {
                plugin.warn("出价失败: " + e.getMessage(), e);
                AuctionMessages.bid__fail.tm(player);
                if (callback != null) callback.accept(false);
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════
    //  一口价
    // ═══════════════════════════════════════════════════════════════

    public void buyNow(@NotNull Player player, @NotNull String auctionId, Consumer<Boolean> callback) {
        SweetPlayerMarket plugin = this.plugin;
        plugin.getScheduler().runTaskAsync(() -> {
            try (Connection conn = plugin.getConnection()) {
                AuctionDatabase db = db();
                Auction auction = db.getAuction(conn, auctionId);
                if (auction == null || !auction.isActive()) {
                    AuctionMessages.buy_now__fail.tm(player);
                    if (callback != null) callback.accept(false);
                    return;
                }
                String key = plugin.getKey(player);
                if (auction.isOwn(key)) {
                    AuctionMessages.buy_now__cannot_own.tm(player);
                    if (callback != null) callback.accept(false);
                    return;
                }
                double price = auction.buyNowPrice();
                if (price <= 0) {
                    AuctionMessages.buy_now__fail.tm(player);
                    if (callback != null) callback.accept(false);
                    return;
                }
                IEconomy currency = economy();
                if (currency == null || !currency.has(player, price)) {
                    AuctionMessages.buy_now__not_enough.tm(player,
                            top.mrxiaom.pluginbase.utils.Pair.of("%amount%", format(price)));
                    if (callback != null) callback.accept(false);
                    return;
                }
                // 1. 预占：状态置 FINISHED，CAS 失败说明已被买/已结束
                Auction reserved = auction.withStatus(AuctionStatus.FINISHED);
                if (!db.updateAuctionIfVersionMatches(conn, reserved, auction.version())) {
                    AuctionMessages.buy_now__fail.tm(player);
                    if (callback != null) callback.accept(false);
                    return;
                }
                // 2. 扣买家款
                if (!currency.takeMoney(player, price)) {
                    rollbackToActive(conn, auction, reserved.version());
                    AuctionMessages.buy_now__not_enough.tm(player,
                            top.mrxiaom.pluginbase.utils.Pair.of("%amount%", format(price)));
                    if (callback != null) callback.accept(false);
                    return;
                }
                // 3. 付卖家（税后）
                double sellerAmount = settleSellerAmount(price);
                if (!currency.giveMoney(plugin.getOfflinePlayer(auction.sellerId()), sellerAmount)) {
                    currency.giveMoney(player, price); // 退款买家
                    rollbackToActive(conn, auction, reserved.version());
                    AuctionMessages.buy_now__fail.tm(player);
                    if (callback != null) callback.accept(false);
                    return;
                }
                // 4. 资金全部到位后再退款旧最高出价者（避免回滚时旧出价者押金已退）
                refundBidder(conn, auction, "buy-now-refund");
                // 5. 记录流水 + 交付物品 + 通知
                db.putBid(conn, new AuctionBid(0, auctionId, key, player.getName(), price,
                        LocalDateTime.now(), AuctionBid.BidType.BUY_NOW));
                // 6. 成交后同步竞拍次数（写入 params，供 %auction_bid_count% 展示）
                ConfigurationSection bidCountParams = copyParamsWithBidCount(auction, 1);
                try {
                    Auction fresh = db.getAuction(conn, auctionId);
                    if (fresh != null) {
                        db.updateAuctionIfVersionMatches(conn, fresh.withParams(bidCountParams), fresh.version());
                    }
                } catch (SQLException ignored) {
                    // 仅展示用途，失败不影响交易
                }
                deliverItem(conn, auction, player, true);
                notifyPlayer(auction.sellerId(), AuctionMessages.sold__seller_notice,
                        top.mrxiaom.pluginbase.utils.Pair.of("%item%", display(auction.item())),
                        top.mrxiaom.pluginbase.utils.Pair.of("%price%", format(sellerAmount)));
                AuctionMessages.buy_now__success.tm(player,
                        top.mrxiaom.pluginbase.utils.Pair.of("%item%", display(auction.item())),
                        top.mrxiaom.pluginbase.utils.Pair.of("%amount%", format(price)));
                broadcast(config().broadcastBuyNow(),
                        "<#2ECC71>✔</#2ECC71> <#ECF0F1>" + player.getName() + "</#ECF0F1>"
                                + " <#7F8C8D>一口价拍下</#7F8C8D> <#FFFFFF>" + display(auction.item()) + "</#FFFFFF>"
                                + " <#7F8C8D>金额</#7F8C8D> <#F5A623>" + format(price) + "</#F5A623>");
                if (callback != null) callback.accept(true);
            } catch (SQLException e) {
                plugin.warn("一口价购买失败: " + e.getMessage(), e);
                AuctionMessages.buy_now__fail.tm(player);
                if (callback != null) callback.accept(false);
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════
    //  取消拍卖
    // ═══════════════════════════════════════════════════════════════

    public void cancelAuction(@NotNull Player player, @NotNull String auctionId, Consumer<Boolean> callback) {
        SweetPlayerMarket plugin = this.plugin;
        plugin.getScheduler().runTaskAsync(() -> {
            try (Connection conn = plugin.getConnection()) {
                AuctionDatabase db = db();
                Auction auction = db.getAuction(conn, auctionId);
                String key = plugin.getKey(player);
                if (auction == null || !auction.isOwn(key)) {
                    AuctionMessages.cancel__not_own.tm(player);
                    if (callback != null) callback.accept(false);
                    return;
                }
                if (!auction.isActive()) {
                    AuctionMessages.cancel__not_active.tm(player);
                    if (callback != null) callback.accept(false);
                    return;
                }
                // 先 CAS 置 CANCELLED，防并发
                Auction updated = auction.withStatus(AuctionStatus.CANCELLED);
                if (!db.updateAuctionIfVersionMatches(conn, updated, auction.version())) {
                    AuctionMessages.cancel__fail.tm(player);
                    if (callback != null) callback.accept(false);
                    return;
                }
                // 退最高出价者押金
                refundBidder(conn, auction, "cancel-refund");
                // 物品退还卖家（背包满 → 待领区）
                deliverItem(conn, auction, player, false);
                AuctionMessages.cancel__success.tm(player);
                if (callback != null) callback.accept(true);
            } catch (SQLException e) {
                plugin.warn("取消拍卖失败: " + e.getMessage(), e);
                AuctionMessages.cancel__fail.tm(player);
                if (callback != null) callback.accept(false);
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════
    //  到期处理（流拍 / 成交）
    // ═══════════════════════════════════════════════════════════════

    /** 供 AuctionExpireTask 定时调用。 */
    public void processExpired() {
        SweetPlayerMarket plugin = this.plugin;
        plugin.getScheduler().runTaskAsync(() -> {
            try (Connection conn = plugin.getConnection()) {
                AuctionDatabase db = db();
                List<Auction> expired = db.getExpiredUpTo(conn, LocalDateTime.now(), 200);
                for (Auction auction : expired) {
                    Auction current = db.getAuction(conn, auction.auctionId());
                    if (current == null || !current.isActive()) continue;
                    if (current.highestBidderId() != null) {
                        settleWinningBid(conn, db, current);
                    } else {
                        settleExpiredNoBid(conn, db, current);
                    }
                }
            } catch (SQLException e) {
                plugin.warn("到期拍卖处理异常: " + e.getMessage(), e);
            }
        });
    }

    /** 有出价者到期 → 成交 */
    private void settleWinningBid(Connection conn, AuctionDatabase db, Auction auction) throws SQLException {
        SweetPlayerMarket plugin = this.plugin;
        IEconomy currency = economy();
        Auction reserved = auction.withStatus(AuctionStatus.FINISHED);
        if (!db.updateAuctionIfVersionMatches(conn, reserved, auction.version())) return;
        double price = auction.currentBid();
        double sellerAmount = settleSellerAmount(price);
        boolean paid = true;
        if (!config().bidPayImmediately() && currency != null) {
            // 非"出价即冻结"模式：此刻才扣买家款
            paid = currency.takeMoney(plugin.getOfflinePlayer(auction.highestBidderId()), price);
        }
        if (currency == null) {
            rollbackToActive(conn, auction, reserved.version());
            return;
        }
        if (!currency.giveMoney(plugin.getOfflinePlayer(auction.sellerId()), sellerAmount)) {
            // 卖家入账失败：必须先把买家已扣的款退回，再回滚拍卖（避免买家钱丢、拍卖却回滚）
            if (!config().bidPayImmediately() && paid) {
                currency.giveMoney(plugin.getOfflinePlayer(auction.highestBidderId()), price);
            }
            rollbackToActive(conn, auction, reserved.version());
            return;
        }
        if (!paid) {
            // 买家扣款失败（非冻结模式）：回滚
            rollbackToActive(conn, auction, reserved.version());
            return;
        }
        // 交付物品给最高出价者
        deliverItem(conn, auction, plugin.getPlayer(auction.highestBidderId()), true);
        notifyPlayer(auction.sellerId(), AuctionMessages.sold__seller_notice,
                top.mrxiaom.pluginbase.utils.Pair.of("%item%", display(auction.item())),
                top.mrxiaom.pluginbase.utils.Pair.of("%price%", format(sellerAmount)));
        notifyPlayer(auction.highestBidderId(), AuctionMessages.sold__bidder_win,
                top.mrxiaom.pluginbase.utils.Pair.of("%item%", display(auction.item())),
                top.mrxiaom.pluginbase.utils.Pair.of("%price%", format(price)));
        broadcast(config().broadcastSell(),
                "<#2ECC71>🔨</#2ECC71> <#FFFFFF>" + display(auction.item()) + "</#FFFFFF>"
                        + " <#7F8C8D>成交!</#7F8C8D> <#ECF0F1>"
                        + (auction.highestBidderName() != null ? auction.highestBidderName() : auction.highestBidderId())
                        + "</#ECF0F1> <#7F8C8D>以</#7F8C8D> <#F5A623>" + format(price) + "</#F5A623>");
    }

    /** 无人出价到期 → 流拍，退物品给卖家 */
    private void settleExpiredNoBid(Connection conn, AuctionDatabase db, Auction auction) throws SQLException {
        SweetPlayerMarket plugin = this.plugin;
        Auction updated = auction.withStatus(AuctionStatus.EXPIRED);
        if (!db.updateAuctionIfVersionMatches(conn, updated, auction.version())) return;
        deliverItem(conn, auction, plugin.getPlayer(auction.sellerId()), false);
        notifyPlayer(auction.sellerId(), AuctionMessages.expired__seller_notice,
                top.mrxiaom.pluginbase.utils.Pair.of("%item%", display(auction.item())));
    }

    // ═══════════════════════════════════════════════════════════════
    //  领取（待领区：params.claim.*）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 领取待领内容。
     *
     * @param sellerSide true=卖家领取款项；false=买家（最高出价者）领取物品
     */
    public void claim(@NotNull Player player, @NotNull String auctionId, boolean sellerSide, Consumer<Boolean> callback) {
        SweetPlayerMarket plugin = this.plugin;
        plugin.getScheduler().runTaskAsync(() -> {
            try (Connection conn = plugin.getConnection()) {
                AuctionDatabase db = db();
                Auction auction = db.getAuction(conn, auctionId);
                if (auction == null) {
                    AuctionMessages.claim__nothing.tm(player);
                    if (callback != null) callback.accept(false);
                    return;
                }
                String key = plugin.getKey(player);
                ConfigurationSection params = auction.params();
                // ── 卖家侧：领取成交款(claim.seller.money) + 流拍/取消退回的物品(claim.seller.items) ──
                if (sellerSide && auction.isOwn(key)) {
                    double money = params.getDouble("claim.seller.money", 0);
                    List<?> rawItems = params.getList("claim.seller.items");
                    boolean hasItems = rawItems != null && !rawItems.isEmpty();
                    if (money <= 0 && !hasItems) {
                        AuctionMessages.claim__nothing.tm(player);
                        if (callback != null) callback.accept(false);
                        return;
                    }
                    // 先给物品（主线程），再异步清 money + items
                    if (hasItems) {
                        List<ItemStack> items = new ArrayList<>();
                        for (Object obj : rawItems) {
                            if (obj instanceof ItemStack) items.add((ItemStack) obj);
                        }
                        final String itemName = items.isEmpty() ? "物品" : display(items.get(0));
                        plugin.getScheduler().runTask(() -> {
                            int given = UtilsGive.giveItems(player, items);
                            if (given == 0) {
                                AuctionMessages.claim__inventory_full.tm(player);
                                if (callback != null) callback.accept(false);
                                return;
                            }
                            finishSellerClaim(auctionId, money, itemName, callback, player);
                        });
                    } else {
                        finishSellerClaim(auctionId, money, null, callback, player);
                    }
                    return;
                }
                // ── 买家侧：领取拍得物品(claim.buyer.items) ──
                if (!sellerSide && key.equals(auction.highestBidderId())) {
                    List<?> raw = params.getList("claim.buyer.items");
                    if (raw == null || raw.isEmpty()) {
                        AuctionMessages.claim__nothing.tm(player);
                        if (callback != null) callback.accept(false);
                        return;
                    }
                    // 主线程给物品
                    plugin.getScheduler().runTask(() -> {
                        List<ItemStack> items = new ArrayList<>();
                        for (Object obj : raw) {
                            if (obj instanceof ItemStack) items.add((ItemStack) obj);
                        }
                        int given = UtilsGive.giveItems(player, items);
                        if (given == 0) {
                            AuctionMessages.claim__inventory_full.tm(player);
                            if (callback != null) callback.accept(false);
                            return;
                        }
                        plugin.getScheduler().runTaskAsync(() -> {
                            try (Connection conn2 = plugin.getConnection()) {
                                Auction fresh = db().getAuction(conn2, auctionId);
                                if (fresh == null) {
                                    if (callback != null) callback.accept(false);
                                    return;
                                }
                                // 已尽力发放（剩余已掉落地面），清空待领区
                                ConfigurationSection p = fresh.params();
                                p.set("claim.buyer.items", null);
                                if (db().updateAuctionIfVersionMatches(conn2, fresh.withParams(p), fresh.version())) {
                                    AuctionMessages.claim__item_success.tm(player,
                                            top.mrxiaom.pluginbase.utils.Pair.of("%item%", display(items.get(0))),
                                            top.mrxiaom.pluginbase.utils.Pair.of("%count%", given));
                                    if (callback != null) callback.accept(true);
                                } else {
                                    if (callback != null) callback.accept(false);
                                }
                            } catch (SQLException e) {
                                plugin.warn("领取物品时更新数据库失败: " + e.getMessage(), e);
                                if (callback != null) callback.accept(false);
                            }
                        });
                    });
                    return;
                }
                AuctionMessages.claim__nothing.tm(player);
                if (callback != null) callback.accept(false);
            } catch (SQLException e) {
                plugin.warn("领取失败: " + e.getMessage(), e);
                AuctionMessages.claim__fail.tm(player);
                if (callback != null) callback.accept(false);
            }
        });
    }

    /** 卖家侧领取收尾：清空 money 与 items 待领区（物品已在主线程发放） */
    private void finishSellerClaim(String auctionId, double money, @Nullable String itemName,
                                   Consumer<Boolean> callback, Player player) {
        plugin.getScheduler().runTaskAsync(() -> {
            try (Connection conn = plugin.getConnection()) {
                Auction fresh = db().getAuction(conn, auctionId);
                if (fresh == null) {
                    if (callback != null) callback.accept(false);
                    return;
                }
                ConfigurationSection p = fresh.params();
                p.set("claim.seller.money", null);
                p.set("claim.seller.items", null);
                if (db().updateAuctionIfVersionMatches(conn, fresh.withParams(p), fresh.version())) {
                    IEconomy currency = economy();
                    if (money > 0 && currency != null) {
                        currency.giveMoney(player, money);
                        AuctionMessages.claim__money_success.tm(player,
                                top.mrxiaom.pluginbase.utils.Pair.of("%amount%", format(money)));
                    }
                    if (itemName != null) {
                        AuctionMessages.claim__item_success.tm(player,
                                top.mrxiaom.pluginbase.utils.Pair.of("%item%", itemName),
                                top.mrxiaom.pluginbase.utils.Pair.of("%count%", 1));
                    }
                    if (callback != null) callback.accept(true);
                } else {
                    if (callback != null) callback.accept(false);
                }
            } catch (SQLException e) {
                plugin.warn("卖家领取时更新数据库失败: " + e.getMessage(), e);
                if (callback != null) callback.accept(false);
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════
    //  查询（供 GUI 使用，同步返回）
    // ═══════════════════════════════════════════════════════════════

    public List<Auction> getActiveAuctions(int page, int size, @NotNull String sort) {
        try (Connection conn = plugin.getConnection()) {
            return db().getActiveAuctions(conn, page, size, sort);
        } catch (SQLException e) {
            plugin.warn("查询活动拍卖失败: " + e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    public int countActiveAuctions() {
        try (Connection conn = plugin.getConnection()) {
            return db().countActiveAuctions(conn);
        } catch (SQLException e) {
            return 0;
        }
    }

    public List<Auction> getMyAuctions(@NotNull Player player, int page, int size) {
        try (Connection conn = plugin.getConnection()) {
            return db().getBySeller(conn, plugin.getKey(player), page, size);
        } catch (SQLException e) {
            plugin.warn("查询我的拍卖失败: " + e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    public List<Auction> getMyBids(@NotNull Player player, int page, int size) {
        try (Connection conn = plugin.getConnection()) {
            return db().getByBidder(conn, plugin.getKey(player), page, size);
        } catch (SQLException e) {
            plugin.warn("查询竞拍记录失败: " + e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    @Nullable
    public Auction getAuction(@NotNull String auctionId) {
        try (Connection conn = plugin.getConnection()) {
            return db().getAuction(conn, auctionId);
        } catch (SQLException e) {
            return null;
        }
    }

    public List<AuctionBid> getBidsByAuction(@NotNull String auctionId, int limit) {
        try (Connection conn = plugin.getConnection()) {
            return db().getBidsByAuction(conn, auctionId, limit);
        } catch (SQLException e) {
            return new ArrayList<>();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  内部工具
    // ═══════════════════════════════════════════════════════════════

    /** 退款旧最高出价者（若存在） */
    private void refundBidder(Connection conn, Auction auction, String reason) {
        if (auction.highestBidderId() == null) return;
        IEconomy currency = economy();
        if (currency != null && config().bidPayImmediately()) {
            currency.giveMoney(plugin.getOfflinePlayer(auction.highestBidderId()), auction.currentBid());
            notifyPlayer(auction.highestBidderId(), AuctionMessages.sold__bidder_lose,
                    top.mrxiaom.pluginbase.utils.Pair.of("%item%", display(auction.item())));
        }
    }

    /** 回滚到 ACTIVE（CAS），用于事务中途失败 */
    private void rollbackToActive(Connection conn, Auction auction, int expectedVersion) {
        try {
            db().updateAuctionIfVersionMatches(conn, auction.withStatus(AuctionStatus.ACTIVE), expectedVersion);
        } catch (SQLException ignored) {
        }
    }

    /** 成交税后卖家所得 */
    private double settleSellerAmount(double price) {
        if (!config().taxEnabled()) return price;
        return price * (1 - config().taxPercent() / 100.0);
    }

    /**
     * 复制 params 并在 bid-count 上累加 delta。
     * 复制而非原地修改：Auction 不可变，原 params 可能仍被列表缓存引用。
     */
    private ConfigurationSection copyParamsWithBidCount(Auction auction, int delta) {
        ConfigurationSection src = auction.params();
        ConfigurationSection copy = new MemoryConfiguration();
        for (String k : src.getKeys(false)) {
            copy.set(k, src.get(k));
        }
        copy.set("bid-count", src.getInt("bid-count", 0) + delta);
        return copy;
    }

    /**
     * 交付物品：在线且背包有空 → 直接给；否则写入待领区 params.claim.*。
     * 主线程操作背包。
     */
    private void deliverItem(Connection conn, Auction auction, @Nullable Player target, boolean toBuyer) {
        if (target == null || !target.isOnline()) {
            queueClaim(conn, auction, toBuyer, null);
            return;
        }
        plugin.getScheduler().runTask(() -> {
            int firstEmpty = target.getInventory().firstEmpty();
            if (firstEmpty == -1) {
                queueClaim(conn, auction, toBuyer, null);
                return;
            }
            target.getInventory().addItem(auction.item());
        });
    }

    /** 写入待领区（主线程或异步均可，本方法内部切异步写库） */
    private void queueClaim(Connection conn, Auction auction, boolean toBuyer, ItemStack fallbackItem) {
        plugin.getScheduler().runTaskAsync(() -> {
            try (Connection c = plugin.getConnection()) {
                Auction fresh = db().getAuction(c, auction.auctionId());
                if (fresh == null) return;
                ConfigurationSection params = fresh.params();
                if (toBuyer) {
                    List<Object> items = new ArrayList<>();
                    Object existing = params.get("claim.buyer.items");
                    if (existing instanceof List) items.addAll((List<?>) existing);
                    ItemStack item = fallbackItem != null ? fallbackItem : auction.item();
                    items.add(item);
                    params.set("claim.buyer.items", items);
                } else {
                    params.set("claim.seller.items", true); // 标记卖家有待领（实际是物品已在 params 外？统一走物品）
                    List<Object> items = new ArrayList<>();
                    Object existing = params.get("claim.seller.items");
                    if (existing instanceof List) items.addAll((List<?>) existing);
                    ItemStack item = fallbackItem != null ? fallbackItem : auction.item();
                    items.add(item);
                    params.set("claim.seller.items", items);
                }
                db().updateAuctionIfVersionMatches(c, fresh.withParams(params), fresh.version());
            } catch (SQLException e) {
                plugin.warn("写入待领区失败: " + e.getMessage(), e);
            }
        });
    }

    private void rollbackItem(Player player, ItemStack item) {
        plugin.getScheduler().runTask(() -> {
            int firstEmpty = player.getInventory().firstEmpty();
            if (firstEmpty == -1) {
                player.getWorld().dropItem(player.getLocation(), item);
            } else {
                player.getInventory().addItem(item);
            }
        });
    }

    private void notifyPlayer(String playerId, top.mrxiaom.pluginbase.func.language.Message message,
                              top.mrxiaom.pluginbase.utils.Pair<String, Object>... pairs) {
        Player player = plugin.getPlayer(playerId);
        if (player != null && player.isOnline()) {
            if (pairs == null || pairs.length == 0) {
                message.tm(player);
            } else {
                message.tm(player, pairs);
            }
        }
    }

    private void broadcast(boolean enabled, String miniMessage) {
        if (!enabled || "NONE".equalsIgnoreCase(config().broadcastRange())) return;
        net.kyori.adventure.text.Component component =
                net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(miniMessage);
        plugin.getScheduler().runTask(() -> {
            for (Player online : Bukkit.getOnlinePlayers()) {
                // AdventureUtil.of(player) 为 pluginbase 提供的跨版本发送入口（spigot-api 编译期可用）
                top.mrxiaom.pluginbase.utils.AdventureUtil.of(online).sendMessage(component);
            }
        });
    }

    private String display(ItemStack item) {
        String name = plugin.getItemDisplayName(item);
        return name != null ? name : item.getType().name();
    }

    private String format(double amount) {
        return plugin.displayNames().formatMoney(amount);
    }
}
