package top.mrxiaom.sweet.playermarket.auction;

import org.bukkit.configuration.MemoryConfiguration;
import top.mrxiaom.sweet.playermarket.SweetPlayerMarket;
import top.mrxiaom.sweet.playermarket.func.AbstractModule;

/**
 * 到期拍卖处理定时任务。
 *
 * <p>对应 PlayerAuctions 的 AuctionExpirer（原每 30 秒异步跑一次）。
 * 本模块不带 @AutoRegister，由 SweetPlayerMarket.beforeEnable 显式 new 注册，
 * 保证 reloadConfig 阶段 AuctionConfig 等依赖模块已就绪。
 * 防御性读取配置：instanceOf 失败时回退默认 30 秒。
 */
public class AuctionExpireTask extends AbstractModule {
    private static final long DEFAULT_INTERVAL_SECONDS = 30;

    private boolean started = false;

    public AuctionExpireTask(SweetPlayerMarket plugin) {
        super(plugin);
    }

    @Override
    public void reloadConfig(MemoryConfiguration config) {
        if (!started) {
            started = true;
            long interval = DEFAULT_INTERVAL_SECONDS;
            try {
                interval = Math.max(5, AuctionConfig.inst().expireIntervalSeconds());
            } catch (IllegalStateException ignored) {
                // 模块加载阶段 AuctionConfig 可能尚未注册，回退默认 30 秒（与 PlayerAuctions 一致）
            }
            plugin.getScheduler().runTaskTimerAsync(this::run, 20 * 5L, interval * 20L);
        }
    }

    /** 每周期执行：处理到期拍卖（成交或流拍） */
    public void run() {
        AuctionService.inst().processExpired();
    }
}
