package top.mrxiaom.sweet.playermarket.auction;

import com.zaxxer.hikari.HikariConfig;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.mrxiaom.pluginbase.database.IDatabase;
import top.mrxiaom.sweet.playermarket.SweetPlayerMarket;
import top.mrxiaom.sweet.playermarket.data.Searching;
import top.mrxiaom.sweet.playermarket.func.AbstractPluginHolder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 拍卖数据库（auctions 主表 + auction_bids 竞拍流水表）。
 *
 * <p>复用 SweetPlayerMarket 的 HikariCP 连接池（SQLite/MySQL 皆可），
 * 表名前缀由 database.yml 的 table_prefix 决定。
 *
 * <p><b>SQL 安全性说明</b>：所有用户输入（玩家 ID、金额、拍卖 ID、物品数据）均通过
 * PreparedStatement 参数绑定（? 占位）；字符串拼接仅用于两类<em>非用户输入</em>常量：
 * ① 表名（来自配置 table_prefix）；② ORDER BY 白名单（本类 switch 映射，非法值回退默认）。
 * 因此不存在 SQL 注入面。
 *
 * <p>乐观锁核心：{@link #updateAuctionIfVersionMatches} 以
 * {@code WHERE auction_id=? AND version=? AND status=ACTIVE} 做 CAS，
 * 影响行数为 1 才算成功 —— 对应 PlayerAuctions 的
 * {@code updateAuctionIfVersionMatches} 语义。
 */
public class AuctionDatabase extends AbstractPluginHolder implements IDatabase {
    protected String TABLE_AUCTIONS;
    protected String TABLE_BIDS;

    public AuctionDatabase(SweetPlayerMarket plugin) {
        super(plugin, true);
    }

    public String getTableAuctions() { return TABLE_AUCTIONS; }
    public String getTableBids() { return TABLE_BIDS; }

    @Override
    public void beforeReload(HikariConfig hikariConfig, YamlConfiguration config) {
        // SQLite 扩展支持（与 MarketplaceDatabase 保持一致）
        if (plugin.options.database().isSQLite()) {
            java.util.Properties sqliteProps = new java.util.Properties();
            sqliteProps.put("enable_load_extension", "true");
            hikariConfig.setDataSourceProperties(sqliteProps);
        }
    }

    @Override
    public void reload(Connection conn, String tablePrefix) throws SQLException {
        TABLE_AUCTIONS = tablePrefix + "auctions";
        TABLE_BIDS = tablePrefix + "auction_bids";
        try (PreparedStatement ps = conn.prepareStatement(
                "CREATE TABLE if NOT EXISTS `" + TABLE_AUCTIONS + "`(" +
                        "`auction_id` VARCHAR(48) PRIMARY KEY," +   // 拍卖 ID
                        "`seller` VARCHAR(48)," +                   // 卖家 playerId
                        "`item` LONGTEXT," +                        // ItemSerializerManager YAML(item-nbt)
                        "`start_price` DOUBLE," +                   // 起拍价
                        "`current_bid` DOUBLE," +                   // 当前最高出价
                        "`highest_bidder` VARCHAR(48) NULL," +      // 最高出价者 playerId
                        "`buy_now_price` DOUBLE DEFAULT 0," +       // 一口价 (0=无)
                        "`bid_increment` DOUBLE DEFAULT 0," +       // 加价幅度 (0=默认)
                        "`create_time` DATETIME," +                 // 创建时间
                        "`end_time` DATETIME," +                    // 截止时间
                        "`auto_extend` TINYINT(1) DEFAULT 0," +     // 自动延期开关
                        "`extend_minutes` INT DEFAULT 0," +         // 顺延分钟数
                        "`status` INT DEFAULT 0," +                 // AuctionStatus.value()
                        "`version` INT DEFAULT 1," +                // 乐观锁版本号
                        "`data` LONGTEXT" +                         // YAML: seller.name + item(-nbt) + params
                ");"
        )) { ps.execute(); }
        try (PreparedStatement ps = conn.prepareStatement(
                "CREATE TABLE if NOT EXISTS `" + TABLE_BIDS + "`(" +
                        "`bid_id` INTEGER PRIMARY KEY " +
                        (plugin.options.database().isSQLite() ? "AUTOINCREMENT" : "AUTO_INCREMENT") + "," +
                        "`auction_id` VARCHAR(48)," +
                        "`bidder` VARCHAR(48)," +
                        "`bidder_name` VARCHAR(48)," +
                        "`amount` DOUBLE," +
                        "`bid_time` DATETIME," +
                        "`bid_type` INT DEFAULT 0" +               // 0=出价 1=一口价
                        ");"
        )) { ps.execute(); }
        try (PreparedStatement ps = conn.prepareStatement(
                "CREATE INDEX if NOT EXISTS idx_auction_bids_auction ON `" + TABLE_BIDS + "`(`auction_id`);"
        )) { ps.execute(); }
        try (PreparedStatement ps = conn.prepareStatement(
                "CREATE INDEX if NOT EXISTS idx_auctions_status ON `" + TABLE_AUCTIONS + "`(`status`,`end_time`);"
        )) { ps.execute(); }
    }

    // ─────────────────────────── 写入 ───────────────────────────

    /** 插入新拍卖 */
    public boolean insertAuction(Connection conn, @NotNull Auction a) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO `" + TABLE_AUCTIONS + "`(" +
                        "`auction_id`,`seller`,`item`,`start_price`,`current_bid`,`highest_bidder`," +
                        "`buy_now_price`,`bid_increment`,`create_time`,`end_time`,`auto_extend`," +
                        "`extend_minutes`,`status`,`version`,`data`) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
        )) {
            ps.setString(1, a.auctionId());
            ps.setString(2, a.sellerId());
            ps.setString(3, ""); // item 列不单独使用（物品数据统一存 data 列，避免重复冗余）
            ps.setDouble(4, a.startPrice());
            ps.setDouble(5, a.currentBid());
            ps.setString(6, a.highestBidderId());
            ps.setDouble(7, a.buyNowPrice());
            ps.setDouble(8, a.bidIncrement());
            ps.setString(9, Searching.format(a.createdAt()));
            ps.setString(10, Searching.format(a.endAt()));
            ps.setInt(11, a.autoExtend() ? 1 : 0);
            ps.setLong(12, a.extendMinutes());
            ps.setInt(13, a.status().value());
            ps.setInt(14, a.version());
            ps.setString(15, a.data().saveToString()); // data 列：seller.name + item(-nbt) + params
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * 乐观锁 CAS 更新。仅当 auction_id + version + ACTIVE 均匹配时更新，
     * 版本号自增，返回是否成功（并发冲突返回 false）。
     */
    public boolean updateAuctionIfVersionMatches(Connection conn, @NotNull Auction a, int expectedVersion) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE `" + TABLE_AUCTIONS + "` SET " +
                        "`current_bid`=?,`highest_bidder`=?,`buy_now_price`=?,`bid_increment`=?," +
                        "`end_time`=?,`auto_extend`=?,`extend_minutes`=?,`status`=?,`version`=version+1,`data`=? " +
                        "WHERE `auction_id`=? AND `version`=? AND `status`=0"
        )) {
            ps.setDouble(1, a.currentBid());
            ps.setString(2, a.highestBidderId());
            ps.setDouble(3, a.buyNowPrice());
            ps.setDouble(4, a.bidIncrement());
            ps.setString(5, Searching.format(a.endAt()));
            ps.setInt(6, a.autoExtend() ? 1 : 0);
            ps.setLong(7, a.extendMinutes());
            ps.setInt(8, a.status().value());
            ps.setString(9, a.data().saveToString());
            ps.setString(10, a.auctionId());
            ps.setInt(11, expectedVersion);
            return ps.executeUpdate() == 1;
        }
    }

    /** 记录竞拍流水 */
    public boolean putBid(Connection conn, @NotNull AuctionBid bid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO `" + TABLE_BIDS + "`(`auction_id`,`bidder`,`bidder_name`,`amount`,`bid_time`,`bid_type`) " +
                        "VALUES(?,?,?,?,?,?)"
        )) {
            ps.setString(1, bid.auctionId());
            ps.setString(2, bid.bidderId());
            ps.setString(3, bid.bidderName());
            ps.setDouble(4, bid.amount());
            ps.setString(5, Searching.format(bid.bidTime()));
            ps.setInt(6, bid.type().value());
            return ps.executeUpdate() == 1;
        }
    }

    // ─────────────────────────── 查询 ───────────────────────────

    @Nullable
    public Auction getAuction(Connection conn, String auctionId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM `" + TABLE_AUCTIONS + "` WHERE `auction_id`=?"
        )) {
            ps.setString(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRowToAuction(rs);
            }
        }
        return null;
    }

    /** 活动拍卖分页查询。sort: time_left | price_asc | price_desc | newest（白名单映射，防注入） */
    public List<Auction> getActiveAuctions(Connection conn, int page, int size, @NotNull String sort) throws SQLException {
        String order;
        switch (sort) {
            case "price_asc":  order = "`current_bid` ASC";  break;
            case "price_desc": order = "`current_bid` DESC"; break;
            case "newest":     order = "`create_time` DESC"; break;
            case "time_left":
            default:           order = "`end_time` ASC";     break;
        }
        List<Auction> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM `" + TABLE_AUCTIONS + "` WHERE `status`=0 ORDER BY " + order +
                        " LIMIT ? OFFSET ?"
        )) {
            ps.setInt(1, size);
            ps.setInt(2, (page - 1) * size);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapRowToAuction(rs));
            }
        }
        return list;
    }

    public int countActiveAuctions(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM `" + TABLE_AUCTIONS + "` WHERE `status`=0"
        ); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** 某卖家名下的"我的拍卖"：进行中（ACTIVE）+ 有待领内容（data 含 claim 标记），按创建时间倒序分页 */
    public List<Auction> getBySeller(Connection conn, String sellerId, int page, int size) throws SQLException {
        List<Auction> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM `" + TABLE_AUCTIONS + "` WHERE `seller`=? AND (`status`=0 OR `data` LIKE '%claim:%') " +
                        "ORDER BY `create_time` DESC LIMIT ? OFFSET ?"
        )) {
            ps.setString(1, sellerId);
            ps.setInt(2, size);
            ps.setInt(3, (page - 1) * size);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapRowToAuction(rs));
            }
        }
        return list;
    }

    /** "我的拍卖"总数（与 getBySeller 同条件，供 GUI 分页标题） */
    public int countBySeller(Connection conn, String sellerId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM `" + TABLE_AUCTIONS + "` WHERE `seller`=? AND (`status`=0 OR `data` LIKE '%claim:%')"
        )) {
            ps.setString(1, sellerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** 卖家"进行中"拍卖数（用于创建拍卖的上限校验，不含已结束/已取消的历史） */
    public int countActiveBySeller(Connection conn, String sellerId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM `" + TABLE_AUCTIONS + "` WHERE `seller`=? AND `status`=0"
        )) {
            ps.setString(1, sellerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** 我出价过（或一口价买过）的拍卖，按最后出价时间倒序去重分页 */
    public List<Auction> getByBidder(Connection conn, String bidderId, int page, int size) throws SQLException {
        List<Auction> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT a.* FROM `" + TABLE_AUCTIONS + "` a JOIN " +
                        "(SELECT `auction_id`, MAX(`bid_id`) max_bid FROM `" + TABLE_BIDS +
                        "` WHERE `bidder`=? GROUP BY `auction_id`) b ON a.`auction_id`=b.`auction_id` " +
                        "ORDER BY b.max_bid DESC LIMIT ? OFFSET ?"
        )) {
            ps.setString(1, bidderId);
            ps.setInt(2, size);
            ps.setInt(3, (page - 1) * size);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapRowToAuction(rs));
            }
        }
        return list;
    }

    public int countByBidder(Connection conn, String bidderId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(DISTINCT `auction_id`) FROM `" + TABLE_BIDS + "` WHERE `bidder`=?"
        )) {
            ps.setString(1, bidderId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** 批量取出已过期且仍 ACTIVE 的拍卖（流拍处理，batch 上限防止一次查询过大） */
    public List<Auction> getExpiredUpTo(Connection conn, LocalDateTime now, int batch) throws SQLException {
        List<Auction> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM `" + TABLE_AUCTIONS + "` WHERE `status`=0 AND `end_time`<=? LIMIT ?"
        )) {
            ps.setString(1, Searching.format(now));
            ps.setInt(2, batch);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapRowToAuction(rs));
            }
        }
        return list;
    }

    /** 某拍卖的竞拍流水（最新在前） */
    public List<AuctionBid> getBidsByAuction(Connection conn, String auctionId, int limit) throws SQLException {
        List<AuctionBid> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM `" + TABLE_BIDS + "` WHERE `auction_id`=? ORDER BY `bid_id` DESC LIMIT ?"
        )) {
            ps.setString(1, auctionId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new AuctionBid(
                            rs.getLong("bid_id"),
                            rs.getString("auction_id"),
                            rs.getString("bidder"),
                            rs.getString("bidder_name"),
                            rs.getDouble("amount"),
                            Searching.format(rs.getString("bid_time")),
                            AuctionBid.BidType.valueOf(rs.getInt("bid_type"))
                    ));
                }
            }
        }
        return list;
    }

    // ─────────────────────────── 行映射 ───────────────────────────

    private Auction mapRowToAuction(ResultSet rs) throws SQLException {
        YamlConfiguration data = new YamlConfiguration();
        try {
            data.loadFromString(rs.getString("data"));
        } catch (Exception ignored) {
            data = new YamlConfiguration();
        }
        return Auction.fromData(
                rs.getString("auction_id"),
                rs.getString("seller"),
                rs.getDouble("start_price"),
                rs.getDouble("current_bid"),
                rs.getString("highest_bidder"),
                rs.getDouble("buy_now_price"),
                rs.getDouble("bid_increment"),
                Searching.format(rs.getString("create_time")),
                Searching.format(rs.getString("end_time")),
                rs.getInt("auto_extend") == 1,
                rs.getInt("extend_minutes"),
                AuctionStatus.valueOf(rs.getInt("status")),
                rs.getInt("version"),
                data
        );
    }
}
