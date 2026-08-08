package top.mrxiaom.sweet.playermarket.auction;

/**
 * 拍卖状态机（移植自 PlayerAuctions AuctionStatus 并扩展竞拍语义）
 */
public enum AuctionStatus {
    /** 进行中：可出价、可一口价、可取消 */
    ACTIVE(0),
    /** 已成交：最高出价者/一口价买家获得物品，卖家收款 */
    FINISHED(1),
    /** 已取消：物品退还卖家，押金退还最高出价者 */
    CANCELLED(2),
    /** 已流拍（到期无人出价）：物品退还卖家 */
    EXPIRED(3);

    private final int value;
    AuctionStatus(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    public static AuctionStatus valueOf(int value) {
        for (AuctionStatus status : values()) {
            if (status.value == value) {
                return status;
            }
        }
        return null;
    }
}
