# XMPlayerMarketPlus 集成设计文档

> 目标：以 **SweetPlayerMarket**（Java 8 / mrxiaom pluginbase / HikariCP / YAML-GUI / ActionProviders）为基础，完整移植 **PlayerAuctions** 的拍卖业务能力（创建/竞拍/一口价/自动延期/取消/手续费/邮箱领取），补齐 6 个 GUI 界面与 4 类打开入口，产出功能完备的 **XMPlayerMarketPlus**。

---

## 1. 集成目标与原则

### 1.1 目标功能矩阵（对照 PlayerAuctions 能力）

| PlayerAuctions 能力 | XMPlayerMarketPlus 方案 | 状态 |
|---|---|---|
| 拍卖创建（/ah sell） | `/spm auction create` + 创建 GUI（选物品/起拍价/一口价/时长/自动延期） | 新增 |
| 一口价购买（buyItem） | 竞拍 + 一口价并存：`ActionAuctionBuyNow` | 增强 |
| 竞拍出价 | 新增 `ActionAuctionBid`，最高出价 + 加价幅度 + 出价即锁定 | **补全** |
| 自动延期 | 出价在最后 N 秒时 endAt 自动顺延（可配置） | **补全** |
| 取消上架 | `ActionAuctionCancel`，退物品/退当前出价者押金 | 移植 |
| 手续费结算 | 上架费（CreateCost 式）+ 成交税（tax-percentage），支持转入服务器账户 | 增强 |
| 邮件领取 | 复用"我的商品"领取体系：成交款/流拍物品/退款进 `params` 待领，`ActionAuctionClaim` 领取 | 移植 |
| 流拍处理 | `AuctionExpireTask` 每 30s 异步扫描 | 移植 |
| 交易历史 | 新增竞拍记录表（bids）+ 拍卖历史视图 | 增强 |
| GUI（主菜单/浏览/我的/邮箱/历史） | 6 个新 GUI（主菜单/列表/详情/我的拍卖/竞拍记录/创建拍卖） | 新增 |
| 打开入口 | 命令 `/spm auction`、拍卖令牌右键、Citizens NPC、现有市场 GUI 按钮 | 新增 |

### 1.2 设计原则

1. **零侵入优先**：新增代码全部落在 `auction.*` 新包；对既有类的修改仅限 3 个接入点（`SweetPlayerMarket.beforeEnable` 注册、`CommandMain` 增子命令、`Messages` 增消息），且以 patch 形式提供。
2. **复用 SweetPlayerMarket 基础设施**：数据库（HikariCP）、经济（`IEconomy` 多币种）、物品序列化（`ItemSerializerManager`）、GUI（`AbstractGuiModule` + YAML 布局 + `LoadedIcon` 动作）、i18n（`LanguageManager`）、调度器（`plugin.getScheduler()`）。
3. **PlayerAuctions 设计精华移植**：乐观锁版本号 CAS、预占成交、失败补偿回滚、异步 + 实体线程切换、流拍批量处理。
4. **Java 8 兼容**：不使用 record/var/switch 表达式（SweetPlayerMarket `targetJavaVersion = 8`）。

---

## 2. 总体架构

```
┌────────────────────────────────────────────────────────────────┐
│ 入口层：/spm auction 命令  |  拍卖令牌右键  |  NPC 右键  | GUI 按钮 │
├────────────────────────────────────────────────────────────────┤
│ GUI 层(auction 包)：GuiAuctionMain / GuiAuctionList            │
│   / GuiAuctionDetail / GuiAuctionMy / GuiAuctionBids           │
│   / GuiAuctionCreate（继承 AbstractGuiModule，YAML 布局）       │
├────────────────────────────────────────────────────────────────┤
│ 动作层(actions 包)：ActionAuctionOpen/Bid/BuyNow/Cancel/       │
│   Claim/Page/Sort（IAction + PROVIDER，GUI 字符串驱动）         │
├────────────────────────────────────────────────────────────────┤
│ 业务层(auction 包)：AuctionService（核心事务）                  │
│   AuctionExpireTask（30s 流拍）AuctionNotice（通知）            │
├────────────────────────────────────────────────────────────────┤
│ 存储层：AuctionDatabase（auctions 表 + bids 表，HikariCP）      │
├────────────────────────────────────────────────────────────────┤
│ 复用层：IEconomy(Vault/Points/MPoints/Coins/Custom)            │
│   ItemSerializerManager(NBT Base64)  Scheduler  LanguageManager│
└────────────────────────────────────────────────────────────────┘
```

### 2.1 类清单与职责

| 类 | 职责 |
|---|---|
| `auction.Auction` | 拍卖不可变模型（POJO，含版本号） |
| `auction.AuctionStatus` | ACTIVE/FINISHED/CANCELLED/EXPIRED |
| `auction.AuctionBid` | 竞拍记录模型（bidder/amount/time/type） |
| `auction.AuctionService` | 创建/出价/一口价/取消/延期/到期/领取全流程（乐观锁） |
| `auction.AuctionDatabase` | auctions 表 + bids 表 + 查询分页 |
| `auction.AuctionExpireTask` | 30s 定时流拍处理 |
| `auction.AuctionConfig` | auction.yml 配置映射 |
| `auction.AuctionMessages` | 拍卖消息注册（LanguageManager） |
| `gui.auction.GuiAuctionMain` | 拍卖主菜单（导航） |
| `gui.auction.GuiAuctionList` | 拍卖浏览（分页/排序/分类） |
| `gui.auction.GuiAuctionDetail` | 拍卖详情（出价/一口价/取消） |
| `gui.auction.GuiAuctionMy` | 我的拍卖（管理/取消/领取） |
| `gui.auction.GuiAuctionBids` | 竞拍记录（我出价过的） |
| `gui.auction.GuiAuctionCreate` | 创建拍卖向导 |
| `actions.ActionAuctionOpen` | `[auction-open:list]` 等打开动作 |
| `actions.ActionAuctionBid` | `[auction-bid]` 出价动作 |
| `actions.ActionAuctionBuyNow` | `[auction-buy-now]` 一口价动作 |
| `actions.ActionAuctionCancel` | `[auction-cancel]` 取消动作 |
| `actions.ActionAuctionClaim` | `[auction-claim]` 领取动作 |
| `commands.arguments.AuctionArguments` | `/spm auction ...` 参数解析 |
| `listener.AuctionInteractListener` | 令牌右键/NPC 右键打开入口 |

---

## 3. 数据模型设计

### 3.1 `Auction`（继承 PlayerAuctions 设计 + 竞拍扩展）

```java
public final class Auction {
    private final String auctionId;        // UUID 字符串（与 shop_id 同风格）
    private final String sellerId;         // 卖家 playerId（online-mode 决定 UUID/名）
    private final String sellerName;
    private final ItemStack item;          // 直接持 ItemStack（经 ItemSerializerManager 入库）
    private final double startPrice;       // 起拍价
    private final double currentBid;       // 当前最高出价
    private final String highestBidderId;  // 最高出价者（可空）
    private final String highestBidderName;
    private final double buyNowPrice;      // 一口价（<=0 表示无）
    private final double bidIncrement;     // 加价幅度（<=0 表示默认值）
    private final LocalDateTime createdAt;
    private final LocalDateTime endAt;     // 截止时间（自动延期时顺延）
    private final boolean autoExtend;      // 是否自动延期
    private final long extendMinutes;      // 最后 N 分钟内出价 → 顺延 N 分钟
    private final AuctionStatus status;
    private final int version;             // 乐观锁版本号
    // + withStatus / withVersion / 各字段 getter
}
```

### 3.2 `AuctionBid`（竞拍记录）

```java
public final class AuctionBid {
    private final long bidId;              // 自增主键
    private final String auctionId;
    private final String bidderId;
    private final String bidderName;
    private final double amount;           // 出价金额
    private final LocalDateTime bidTime;
    // type: BID(出价)/BUY_NOW(一口价) 用于区分
}
```

### 3.3 `AuctionStatus`

```java
public enum AuctionStatus { ACTIVE, FINISHED, CANCELLED, EXPIRED }
```

### 3.4 状态机（含自动延期）

```
                 createAuction()
                       │
                       ▼
   ACTIVE ◄────────────┼─────────────────────────────┐
     │  │              │                             │
     │  │ 出价(最后 extendMinutes 分钟内)            │
     │  └──► endAt 顺延 extendMinutes（自动延期）      │
     │                                               │
     ├── buyNow 一口价 ──► FINISHED（直接结算）        │
     ├── cancel ─────────► CANCELLED（退物品+退当前出价者）
     └── 到期 AuctionExpireTask ─► EXPIRED（物品退卖家）
          └► 有出价者：FINISHED（最高出价者付款成交，卖家收钱）
```

**结算规则（核心）**：

| 事件 | 资金/物品动作 |
|---|---|
| 出价成功 | 冻结旧最高出价者的钱（退还），扣新出价者 `amount`（即"出价即付款"，成交后无需再付） |
| 一口价 | 买家付 `buyNowPrice`；退还当前最高出价者（若有）；卖家收 `buyNowPrice×(1−tax)` |
| 到期有出价 | 卖家收 `currentBid×(1−tax)`；物品交付买家（背包满→邮箱） |
| 到期无出价 | 物品退卖家（背包满→邮箱）；退还最高出价者（无） |
| 取消 | 物品退卖家；退还当前最高出价者 |
| 流拍/成交款 | 卖家/买家离线 → 款项/物品入"我的商品"待领区（params 机制） |

---

## 4. 数据库设计

复用 `MarketplaceDatabase` 的连接池（HikariCP + `options.registerDatabase`），新建独立表（table_prefix 由 database.yml 控制）：

```sql
-- 拍卖主表
CREATE TABLE IF NOT EXISTS `{prefix}auctions` (
  `auction_id`    VARCHAR(48) PRIMARY KEY,
  `seller`        VARCHAR(48),
  `seller_name`   VARCHAR(48),
  `item`          LONGTEXT,              -- ItemSerializerManager YAML(item-nbt)
  `start_price`   DOUBLE,
  `current_bid`   DOUBLE,
  `highest_bidder` VARCHAR(48) NULL,
  `buy_now_price` DOUBLE DEFAULT 0,      -- 0=无一口价
  `bid_increment` DOUBLE DEFAULT 0,      -- 0=使用默认值
  `create_time`   DATETIME,
  `end_time`      DATETIME,
  `auto_extend`   TINYINT(1) DEFAULT 0,
  `extend_minutes` INT DEFAULT 0,
  `status`        INT,                   -- AuctionStatus.value()
  `version`       INT DEFAULT 1,         -- 乐观锁
  `params`        LONGTEXT               -- 待领款项/物品等扩展参数(YAML)
);

-- 竞拍记录表
CREATE TABLE IF NOT EXISTS `{prefix}auction_bids` (
  `bid_id`     INTEGER PRIMARY KEY AUTOINCREMENT,  -- MySQL: AUTO_INCREMENT
  `auction_id` VARCHAR(48),
  `bidder`     VARCHAR(48),
  `bidder_name` VARCHAR(48),
  `amount`     DOUBLE,
  `bid_time`   DATETIME,
  `bid_type`   INT                                 -- 0=出价 1=一口价
);
CREATE INDEX idx_auction_bids_auction ON `{prefix}auction_bids`(`auction_id`);
CREATE INDEX idx_auctions_status ON `{prefix}auctions`(`status`,`end_time`);
```

**乐观锁关键 SQL**：
```sql
UPDATE {prefix}auctions SET current_bid=?, highest_bidder=?, end_time=?, status=?, version=version+1, ...
WHERE auction_id=? AND version=? AND status=1(ACTIVE);
-- 影响行数=1 才算成功（CAS 语义，对应 PlayerAuctions updateAuctionIfVersionMatches）
```

---

## 5. 经济流程（IEconomy 复用）

SweetPlayerMarket 的 `IEconomy`（pluginbase 扩展）是**同步**方法，与 PlayerAuctions 的异步 `EconomyService` 不同：

```java
public interface IEconomy {
    double get(OfflinePlayer player);
    boolean has(OfflinePlayer player, double money);
    boolean giveMoney(OfflinePlayer player, double money);   // 入账
    boolean takeMoney(OfflinePlayer player, double money);   // 扣款
}
```

集成适配：`AuctionService` 内所有经济操作放在 `plugin.getScheduler().runTaskAsync(...)` 中执行（与 `ActionClaim` 相同的模式），保证不阻塞主线程。多币种通过 `plugin.parseEconomy("Vault"|"PlayerPoints"|"MPoints:sign"|"CoinsEngine:id"|"Custom:id")` 解析，`auction.yml` 配置 `currency` 字段选择拍卖默认货币。

**成交结算伪码（AuctionService.finishAuction）**：
```
async {
  conn = plugin.getConnection(); conn.setAutoCommit(false); try {
    a = db.getAuction(conn, id);
    if (a.status() != ACTIVE) rollback;
    reserved = a.withStatus(FINISHED).withVersion(v+1);
    if (!db.updateAuctionIfVersionMatches(conn, reserved, v)) rollback;  // 预占
    // 结算
    if (buyNow) { buyerPay = buyNowPrice; refundBidder(a.highestBidder, a.currentBid); }
    else        { buyerPay = currentBid; }
    tax = buyerPay * taxPercentage / 100;
    if (!currency.takeMoney(buyer, buyerPay)) { rollback ACTIVE; return; }
    if (!currency.giveMoney(seller, buyerPay - tax)) { rollback; refund buyer; return; }
    give item to buyer (背包→邮箱);  // 实体线程
    db.putBid(conn, buyNow 记录);
    notice/broadcast; commit;
  } catch { rollback; }
}
```
> 说明：PlayerAuctions 采用"出价即付"不可行时，可切换"成交再付"模式（出价不扣款，仅校验 `has`，成交时扣款）。XMPlayerMarketPlus 默认**出价即冻结**（更安全，防资金不足赖账），配置项 `auction.bid-pay-immediately: true/false` 可切换。

---

## 6. GUI 设计

### 6.1 界面总览与打开入口

| GUI | 文件 | 入口 |
|---|---|---|
| 拍卖主菜单 GuiAuctionMain | `gui/auction-main.yml` | `/spm auction`、令牌右键、NPC |
| 拍卖浏览 GuiAuctionList | `gui/auction-list.yml` | 主菜单"浏览拍卖"、`/spm auction list` |
| 拍卖详情 GuiAuctionDetail | `gui/auction-detail.yml` | 浏览列表点击物品 |
| 我的拍卖 GuiAuctionMy | `gui/auction-my.yml` | 主菜单"我的拍卖"、`/spm auction my` |
| 竞拍记录 GuiAuctionBids | `gui/auction-bids.yml` | 主菜单"竞拍记录"、`/spm auction bids` |
| 创建拍卖 GuiAuctionCreate | `gui/auction-create.yml` | 主菜单"创建拍卖"、`/spm auction create`、手持物品时右键令牌 |

### 6.2 GUI 布局（YAML 驱动，复用 AbstractGuiModule）

所有界面遵循 `AbstractGuiSearch` 模式：`inventory` 行字符串定义布局，`main-icons.物` 为商品/拍卖动态槽（出现次数即列表索引），`other-icons` 为静态按钮，点击执行动作字符串。

**auction-list.yml 关键段示例**：

```yaml
title: '<gradient:gold:yellow>拍卖行 - 第 %page%/%max_page% 页</gradient>'
inventory:
  - '#########'
  - '#物物物物物物物#'
  - '#物物物物物物物#'
  - '#物物物物物物物#'
  - '#物物物物物物物#'
  - '#翻类排刷我记创#'
main-icons:
  物:
    display: '&e&l%item%'
    lore:
      - item lore
      - ''
      - '  &f卖家: &e%auction_seller%'
      - '  &f起拍价: &6%auction_start_price%'
      - '  &f当前出价: &a%auction_current_bid%'
      - '  &f一口价: &b%auction_buy_now%'
      - '  &f出价次数: &e%auction_bid_count% &7| &f最高出价者: &e%auction_highest_bidder%'
      - '  &f剩余时间: &e%auction_time_left%'
      - '  &f自动延期: &a%auction_auto_extend%'
      - ''
      - '&a左键 &7| &f查看详情并出价'
  空:
    material: GRAY_STAINED_GLASS_PANE
    display: '&8无拍卖'
other-icons:
  翻:
    material: ARROW
    display: '&e&l翻页'
    lore: ['&7左键: 下一页', '&7右键: 上一页']
    left-click-commands: ['[auction-page]+1']
    right-click-commands: ['[auction-page]-1']
  类:
    material: CHEST
    display: '&e&l分类'
    left-click-commands: ['[auction-open:list]', 'type: auction-sort']   # 示例
  排:
    material: COMPARATOR
    display: '&e&l排序: %auction_sort%'
    left-click-commands: ['[auction-sort]']
  刷:
    material: SNOWBALL
    display: '&a&l刷新'
    left-click-commands: ['[refresh]']
  我:
    material: BOOK
    display: '&d&l我的拍卖'
    left-click-commands: ['[auction-open:my]']
  记:
    material: WRITABLE_BOOK
    display: '&6&l竞拍记录'
    left-click-commands: ['[auction-open:bids]']
  创:
    material: ANVIL
    display: '&2&l创建拍卖'
    left-click-commands: ['[auction-open:create]']
```

### 6.3 详情/创建界面的交互设计

- **GuiAuctionDetail**：`[auction-bid]`（左键出价=当前价+最小加价，右键=输入自定义价）、`[auction-buy-now]`（Shift 左键）、`[auction-cancel]`（自己的拍卖）。出价/购买成功或失败后 `refreshGui()`。
- **GuiAuctionCreate**：仿 `AbstractGuiDeploy` 模式——手持物品即被设为拍卖物（物品槽显示预览），`[auction-create]` 打开聊天输入（Prompter）依次输入起拍价/一口价/时长，或点击预设按钮 `[auction-create-price]+100` 等。确认后调 `AuctionService.createAuction`。

---

## 7. 动作系统设计（新动作）

| 动作 | 字符串格式 | 行为 |
|---|---|---|
| ActionAuctionOpen | `[auction-open:list\|my\|bids\|create\|main]` | 打开对应 GUI |
| ActionAuctionPage | `[auction-page]+1` / `-1` | 拍卖列表翻页 |
| ActionAuctionSort | `[auction-sort]` | 循环切换排序（时间/价格升/价格降/最新） |
| ActionAuctionBid | `[auction-bid]`（+`[auction-bid]input` 走 Prompter） | 对当前详情中的拍卖出价 |
| ActionAuctionBuyNow | `[auction-buy-now]` | 一口价购买 |
| ActionAuctionCancel | `[auction-cancel]` | 取消自己的拍卖 |
| ActionAuctionClaim | `[auction-claim]` | 领取"我的拍卖"中的待领款项/物品 |

所有 PROVIDER 同时支持 YAML 段形式（`type: auction-bid`）。注册位置：`SweetPlayerMarket.beforeEnable` 的 `ActionProviders.registerActionProviders(...)` 追加。

---

## 8. 命令设计

`/spm auction`（在 `CommandMain.onCommand` 加分支，权限 `sweet.playermarket.auction`）：

```
/spm auction                      → 打开拍卖主菜单
/spm auction list [页]            → 拍卖浏览
/spm auction my                   → 我的拍卖
/spm auction bids                 → 竞拍记录
/spm auction create [起拍价] [一口价] [时长]   → 创建拍卖（手持物品）
/spm auction sell <价格> [一口价] [时长]      → 快速上架（命令式，等价 PlayerAuctions /ah sell）
/spm auction cancel <id>          → 取消拍卖
/spm auction reload               → 重载拍卖配置(op)
/spm auction purge <状态>         → 清理历史数据(op)
```

Tab 补全在 `CommandMain.onTabComplete` 增加 `auction` 分支。

---

## 9. 配置设计

### 9.1 auction.yml（新增）

```yaml
auction:
  enabled: true
  currency: Vault                 # 默认货币（IEconomy id）
  max-auctions-per-player: 5      # 单玩家最大同时拍卖数
  default-duration: 24h           # 默认时长（Duration.parse）
  min-duration: 1h
  max-duration: 168h
  min-start-price: 1.0
  bid-increment: 1.0              # 默认加价幅度
  min-bid-increment: 1.0
  bid-pay-immediately: true       # true=出价即扣款冻结; false=成交时扣款
  auto-extend:
    enabled: true
    trigger-minutes: 5            # 最后 5 分钟内出价触发延期
    extend-minutes: 5             # 顺延 5 分钟
  tax:
    enabled: true
    percent: 5                    # 成交税 %
  listing-fee:                    # 上架费（可选）
    enabled: false
    amount: 0
  broadcast:
    on-create: true
    on-sell: true
    on-buy-now: true
    range: GLOBAL
  mailbox:
    enabled: true
    retention-days: 30
  entrances:
    auction-token: true           # 拍卖令牌右键入口
    npc: true                     # NPC 右键入口
    market-gui-button: true       # 现有市场 GUI 加拍卖按钮
```

### 9.2 GUI 文件（新增 6 个）

`gui/auction-main.yml`、`auction-list.yml`、`auction-detail.yml`、`auction-my.yml`、`auction-bids.yml`、`auction-create.yml`。

### 9.3 消息（AuctionMessages，LanguageManager）

新增 `messages.auction.*` 系列：创建成功/失败、出价成功/过低/不足、一口价成功、取消成功、到期通知、领取成功、权限不足等（详见代码 `AuctionMessages.java`）。

---

## 10. 打开入口实现

| 入口 | 实现 |
|---|---|
| 命令 | `CommandMain` 加 `/spm auction` 分支（AuctionArguments） |
| 物品点击（拍卖令牌） | `AuctionInteractListener` 监听 `PlayerInteractEvent`，右键持 `AUCTION_TOKEN_MATERIAL`（配置，默认 `PAPER` 带自定义名/PDC tag）→ `GuiAuctionMain.open` |
| NPC | 软依赖 Citizens：`PlayerInteractEvent` 右键 NPC（`CitizensAPI.getNPCRegistry().isNPC(clicked)`）→ 打开；同时提供 `AuctionService.openMain(player)` API 供其他插件挂钩 |
| 现有市场 GUI 按钮 | 在 `gui/marketplace.yml` 的 `other-icons` 增加"拍"按钮，`left-click-commands: ['[auction-open:main]']`（配置化，不侵入代码） |
| 点击书/告示牌（可选） | 配置 `interact-materials` 列表即可扩展 |

---

## 11. 与既有模块的交互

- **NoticeManager**：上架/成交后 `NoticeManager.inst().updateCreated()` 广播刷新已打开的市场 GUI（可选）；拍卖专用通知走 `AuctionNotice`（基于 `plugin.getScheduler()` + PDC 开关）。
- **MarketAPI**：提供 `AuctionService#api()` 公开创建/出价 API，供其他插件调用。
- **物品序列化**：拍卖物品直接使用 `ItemSerializerManager.setItem(config, item)` 存 NBT Base64，跨版本稳定。
- **显示名**：拍卖物品名用 `plugin.displayNames().getDisplayName(item, player)`；金额用 `displayNames().formatMoney(price)`。
