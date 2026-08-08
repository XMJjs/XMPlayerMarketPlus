package top.mrxiaom.sweet.playermarket.auction;

import java.time.LocalDateTime;

/**
 * 竞拍记录（出价 / 一口价流水）。
 */
public final class AuctionBid {
    public enum BidType {
        /** 普通出价 */
        BID(0),
        /** 一口价购买 */
        BUY_NOW(1);

        private final int value;
        BidType(int value) { this.value = value; }

        public int value() { return value; }

        public static BidType valueOf(int value) {
            for (BidType type : values()) {
                if (type.value == value) return type;
            }
            return null;
        }
    }

    private final long bidId;
    private final String auctionId;
    private final String bidderId;
    private final String bidderName;
    private final double amount;
    private final LocalDateTime bidTime;
    private final BidType type;

    public AuctionBid(long bidId, String auctionId, String bidderId, String bidderName,
                      double amount, LocalDateTime bidTime, BidType type) {
        this.bidId = bidId;
        this.auctionId = auctionId;
        this.bidderId = bidderId;
        this.bidderName = bidderName;
        this.amount = amount;
        this.bidTime = bidTime;
        this.type = type;
    }

    public long bidId() { return bidId; }
    public String auctionId() { return auctionId; }
    public String bidderId() { return bidderId; }
    public String bidderName() { return bidderName; }
    public double amount() { return amount; }
    public LocalDateTime bidTime() { return bidTime; }
    public BidType type() { return type; }
}
