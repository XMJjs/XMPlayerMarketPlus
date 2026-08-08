package top.mrxiaom.sweet.playermarket.auction;

import org.bukkit.configuration.MemoryConfiguration;
import top.mrxiaom.pluginbase.func.AutoRegister;
import top.mrxiaom.sweet.playermarket.func.AbstractModule;

/**
 * 到期拍卖处理定时任务。
 *
 * <p>对应 PlayerAuctions 的 AuctionExpirer（原每 30 秒异步跑一次）。
 * 周期从 AuctionConfig（auction.yml → auction.expire-interval-seconds）读取，
 * 默认 30 秒。
 *
 * <p>启动时机：模块由框架加载并调用 reloadConfig 时，数据库与 AuctionConfig
 * 均已就绪（AuctionConfig priority=990 先加载），因此在此处一次性启动定时器
 * （用 started 防重复）。
 */
@AutoRegister
public class AuctionExpireTask extends AbstractModule {
    private boolean started = false;

    public AuctionExpireTask(SweetPlayerMarket plugin) {
        super(plugin);
    }

    @Override
    public void reloadConfig(MemoryConfiguration config) {
        if (!started) {
            started = true;
            long interval = Math.max(5, AuctionConfig.inst().expireIntervalSeconds());
            plugin.getScheduler().runTaskTimerAsync(this::run, 20 * 5L, interval * 20L);
        }
    }

    /** 每周期执行：处理到期拍卖（成交或流拍） */
    public void run() {
        AuctionService.inst().processExpired();
    }
}
