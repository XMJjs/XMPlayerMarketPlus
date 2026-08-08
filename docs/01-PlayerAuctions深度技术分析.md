# PlayerAuctions 插件深度技术分析报告

> 分析对象：`D:\AI\Github\PlayerAuctions`（git 仓库 `karta-auction-house`，当前版本 3.1.0）
> 分析日期：2026-08-08
> 目的：为 SweetPlayerMarket → XMPlayerMarketPlus 的拍卖功能集成提供完整、可落地的逻辑蓝本。

---

## 目录

1. [项目概览](#1-项目概览)
2. [启动流程与架构分层](#2-启动流程与架构分层)
3. [核心功能模块详解](#3-核心功能模块详解)
4. [数据结构全景](#4-数据结构全景)
5. [经济系统交互](#5-经济系统交互)
6. [GUI 界面设计](#6-gui-界面设计)
7. [命令系统](#7-命令系统)
8. [配置文件全解析](#8-配置文件全解析)
9. [关键类与方法索引](#9-关键类与方法索引)
10. [现有实现缺口（集成时需要补全）](#10-现有实现缺口)

---

## 1. 项目概览

### 1.1 基本信息

| 项 | 值 |
|---|---|
| 插件名 | PlayerAuctions（包前缀 `com.minekarta.playerauction`） |
| 版本 | 3.1.0 |
| 构建 | Maven + maven-shade-plugin |
| 编译目标 | Java 25 |
| 服务端目标 | Paper **api-version 26.2**（2026 版 Paper） |
| 主类 | `com.minekarta.playerauction.PlayerAuction` |
| 硬依赖 | Vault |
| 软依赖 | PlaceholderAPI、KartaEmeraldCurrency |
| Folia | `folia-supported: true`，通过反射兼容层 `FoliaCompat` 同时支持 Paper/Spigot/Folia/Canvas |

### 1.2 依赖库（shade 打包）

| 库 | 用途 | relocation |
|---|---|---|
| paper-api | Bukkit API | -（provided） |
| VaultAPI 1.7.1 | 经济 | -（provided） |
| PlaceholderAPI 2.11.5 | 占位符 | -（provided） |
| Gson 2.10.1 | JSON 持久化 | - |
| snakeyaml 2.2 | YAML | `lib.yaml` |
| InventoryFramework 0.11.5 | GUI（已声明未实际使用） | `lib.inventoryframework` |
| cloud 2.0.0-beta.16 | 命令框架（Brigadier） | `lib.cloud` |
| HikariCP/MySQL/protobuf | SQLite/MySQL 存储（SQLite 实现存在但未启用） | `lib.hikaricp` 等 |

### 1.3 API 兼容性要点（集成时的重要参考）

- **Adventure API（Paper 原生）**：消息、GUI 物品显示名/Lore 全部使用 `net.kyori.adventure.text.Component` + MiniMessage；自定义 `MessageParser` 支持 `&a` 旧版颜色、`&#RRGGBB` 十六进制、`&rgb(r,g,b)` 与 `<tag>` MiniMessage 四类格式自动识别。
- **Folia 兼容**：所有"操作玩家实体"（打开 GUI、加/扣背包、掉落物品）必须走 `EntityScheduler`，全局操作用 `GlobalRegionScheduler`，异步用 `AsyncScheduler`；`FoliaCompat` 通过反射 + `Class.forName("io.papermc.paper.threadedregions.RegionizedServer")` 检测 Folia（**不能**用 paper-api 内的 scheduler 包检测，否则普通 Paper 会被误判）。
- **BukkitRunnable 不可用**：定时任务改用 `FoliaCompat.runAsyncTimer` 持有 `TaskHandle` 句柄。

---

## 2. 启动流程与架构分层

`PlayerAuction.onEnable()` 严格按顺序初始化（**该顺序是集成时的依赖拓扑**）：

```
1. ConfigManager.loadConfigs()                 # config.yml + messages.yml
2. PlayerSettingsService（PDC 玩家设置）
3. NotificationManager + BroadcastManager（通知/广播）
4. 固定线程池 asyncExecutor（可用核数，Worker 线程名 "PlayerAuction-Worker-%d"）
5. StorageFactory 创建三个存储：
     AuctionStorage    -> JsonAuctionStorage    (auctions.json)
     TransactionStorage -> JsonTransactionStorage (transactions.json)
     MailboxStorage    -> JsonMailboxStorage     (mailbox.json)
   并异步 submit init()
6. EconomyRouter.setupEconomy()                 # 无经济提供者则禁用插件
7. PlayerNameCache / TransactionLogger / MailboxService / AuctionService
8. Cloud 命令框架初始化（Brigadier 能力探测）→ AuctionCommand + AdminCommand
9. AuctionExpirer.runTaskTimerAsynchronously(30s 周期)   # 流拍处理
10. FoliaCompat.runAsyncTimer(mailbox 清理, 1h 周期)
```

### 分层架构

```
┌─────────────────────────────────────────────────────────────┐
│ 展示层    MainAuctionGui / MyListingsGui / MailboxGui /       │
│          HistoryGui / GuiItemBuilder / PaginatedGui          │
├─────────────────────────────────────────────────────────────┤
│ 命令层    AuctionCommand(/ah)  AdminCommand(/ahadmin)        │
├─────────────────────────────────────────────────────────────┤
│ 业务层    AuctionService  MailboxService  TransactionLogger  │
│          PlayerSettingsService  AuctionExpirer(定时)         │
├─────────────────────────────────────────────────────────────┤
│ 经济层    EconomyRouter → EconomyService(Vault/KEC)          │
├─────────────────────────────────────────────────────────────┤
│ 存储层    AuctionStorage / TransactionStorage / MailboxStorage│
│          (接口) → Json*/SQLite* (实现) + 内存 List 缓存       │
├─────────────────────────────────────────────────────────────┤
│ 通用层    SerializedItem  MessageParser  DurationParser      │
│          PlaceholderContext  PlayerNameCache  TimeUtil       │
│          FoliaCompat(跨服务端调度)                            │
└─────────────────────────────────────────────────────────────┘
```

---

## 3. 核心功能模块详解

### 3.1 拍卖生命周期（AuctionService）

状态机：

```
                createListing()
                    │
                    ▼
               ┌── ACTIVE ──────────────┐
               │     │                  │
        cancelAuction()│           processExpiredAuctions()
               │     │                  │
               │     ▼                  ▼
               │  CANCELLED          EXPIRED
               │                    (物品退卖家背包/邮箱)
               ▼
        buyItem() 成功 → FINISHED(物品给买家, 钱给卖家)
        buyItem() 并发失败 → 回滚 ACTIVE
```

| 方法 | 职责 | 关键实现 |
|---|---|---|
| `createListing(player, item, price, buyNowPrice, reservePrice, durationMillis)` | 创建拍卖 | `SerializedItem.fromItemStack` 序列化 → `auctionStorage.insertAuction`（异步） |
| `buyItem(buyer, auctionId)` | **一口价购买** | 见 3.2 并发控制 |
| `cancelAuction(player, auctionId)` | 取消上架 | 校验卖家/状态 → `CountDownLatch` 阻塞等待物品退回背包（Folia 实体线程）→ 乐观锁更新状态 |
| `processExpiredAuctions()` | 流拍处理（30s 定时） | 批量 200 条 `findExpiredUpTo` → 逐条加锁重查 → 物品退卖家（在线直退背包，满则掉落/邮箱）→ 状态 EXPIRED + 记日志 |

> 注意：本版本**拍卖即"一口价直购"**（`buyItem`），`price` 字段既是起拍价也是成交价；真正的"竞拍加价"在代码中标注为 TODO（`bid_count`/`highest_bidder` 均为占位符）。**竞拍系统是 XMPlayerMarketPlus 需要补全的最大缺口。**

### 3.2 并发控制（本项目最值得借鉴的设计）

采用 **"预占 + 乐观锁 + 补偿回滚"** 三层策略：

1. **Per-Auction 重入锁**：`ConcurrentHashMap<UUID, ReentrantLock> auctionLocks`，`executeWithLock(auctionId, op)` 在异步线程上锁执行，完成后移除锁条目，防止同一拍卖被并发修改。
2. **版本号乐观锁（CAS）**：`Auction.version` 字段；所有更新走 `updateAuctionIfVersionMatches(auction, expectedVersion)`，SQL 层为 `UPDATE ... WHERE id=? AND version=?`，JSON 层为 `List.set(index, a)` 前比对版本。写冲突返回 false。
3. **购买事务流程（buyItem 完整时序）**：
   ```
   加锁 auctionId
   → findById + 校验 ACTIVE + 非本人
   → 状态置 FINISHED（预占，version+1）→ updateIfVersionMatches 失败则"已被买走"
   → economy.withdraw(buyer, price)
       └ 失败 → 回滚 ACTIVE（version 再+1）→ 提示余额不足
   → economy.deposit(seller, price*(1-tax/100))
       └ 失败 → 退款买家 + 回滚 ACTIVE
   → 物品交付（Folia 实体线程）
       ├ 背包有空 → addItem
       ├ 背包满 → dropItem 到买家脚下
       └ 买家下线（retired 回调）→ 物品进买家邮箱（preferred）→ 邮箱也失败才退款+追回卖家款
   → 通知卖家(auction.sold) + transactionLogger.log(SOLD) + 全服广播
   ```

### 3.3 存储层

- **`AuctionStorage` 接口**（12 个方法）：`init / findById / findActive / findActiveAuctions / findBySeller / findPlayerHistory / countActiveBySeller / countActiveAuctionsByPlayer / countAllActiveAuctions / countActiveAuctions(category,sort,search) / insertAuction / updateAuctionIfVersionMatches / findExpiredUpTo`。
- **`JsonAuctionStorage`（实际启用）**：单线程 Executor + `ReentrantReadWriteLock` 保护内存 `List<Auction>`；全量读写 `auctions.json`；`saveData` 采用 **临时文件 + 3 次重试原子改名** 防止写坏。
- **`SQLiteAuctionStorage`（存在未启用）**：`StorageFactory` 只 new Json 实现；SQLite 版含建表语句、`mapRowToAuction`、分类/排序/分页 SQL，是 JSON 版的数据库替代。
- **`TransactionStorage` + `TransactionLogger`**：`transactions.json` 全量追加；`findTransactionsByPlayer` 按 actor 或 seller 匹配、时间倒序、分页。`TransactionLogger.log(auction, status, buyer, price)` 记录 SOLD/EXPIRED/CANCELLED 全生命周期。

### 3.4 邮箱系统（Mailbox）

- **`MailboxStorage` 接口**：`addItem / getUnclaimedItems(page,limit) / getUnclaimedCount / claimItem / getItem / deleteExpiredItems / deleteOldClaimedItems`。
- **`MailboxItem`**：`type(ITEM|MONEY)` + `reason` + `relatedAuctionId` + `expiresAt`（retention-days 默认 30 天）。两种工厂方法：`forReturnedItem`（流拍/取消退物）、`forMoney`（成交款，离线卖家入邮箱）。
- **领取流程（claimItem）**：校验归属 → 未领取 → 未过期 → 先 `claimItem(id)` 置已领取（防重复）→ ITEM 型经实体线程加背包，MONEY 型 `economy.deposit`。`claimAll` 顺序领取并汇报数量。每小时定时清理过期项。
- **双保险语义**：买家下线时，购买款项已转移，物品先进邮箱完成交易（避免退款+已发货双输局面），仅邮箱也失败才整体退款回滚。

### 3.5 通知与广播

- **`NotificationManager`**：`sendNotification(player, messageKey, placeholders)`，受 `PlayerSettingsService.getNotificationsEnabled`（PDC 持久化，`/ah notify on|off`）开关控制；按 `auction.notification-methods` 配置依次执行 `chat / actionbar / title / sound` 四种渠道；PAPI 可用时经 PAPI 渲染。
- **`BroadcastManager`**：上架/成交全服广播，`range: GLOBAL|WORLD|NONE` 控制范围，消息模板在 messages.yml 的 `broadcast.*`。

### 3.6 玩家设置

- `PlayerSettingsService` 用 **PersistentDataContainer（PDC）+ NamespacedKey("notifications_enabled")** 存储通知开关，默认开。PDC 方案比独立配置更轻量、随玩家存档走。

### 3.7 定时任务

- `AuctionExpirer`：**每 30s** 异步跑 `processExpiredAuctions()`，批量 200。Folia 兼容用 `FoliaCompat.runAsyncTimer` 返回 `TaskHandle`（可 cancel）。
- 邮箱清理：**每小时** `cleanupExpiredItems()`。

---

## 4. 数据结构全景

### 4.1 `Auction`（Java 16 record）

```java
public record Auction(
    UUID id,                  // 拍卖唯一 ID
    UUID seller,              // 卖家 UUID
    SerializedItem item,      // 物品（Base64 序列化）
    double price,             // 起拍价/直购价（本版本合二为一）
    Double buyNowPrice,       // 一口价（可空）
    Double reservePrice,      // 保留价/最低成交价（可空，预留）
    long createdAt,           // 创建时间戳
    long endAt,               // 到期时间戳
    AuctionStatus status,     // ACTIVE/FINISHED/CANCELLED/EXPIRED
    int version               // 乐观锁版本号
) {
    Auction withStatus(AuctionStatus newStatus);           // 不可变更新状态
    Auction withIncrementedVersion();                      // 版本号 +1
}
```

> 设计点评：record 不可变性 + 全字段快照更新，天然适配乐观锁。**缺点**：没有 `highestBidder`/`bidCount`/`bidIncrement` 字段——集成时必须扩展。

### 4.2 `SerializedItem`（物品序列化）

- `BukkitObjectOutputStream` → Base64（`ItemStack` Java 序列化），`fromItemStack / toItemStack / fromBase64 / getBase64`。
- `toString()` 被重写为 `SerializedItem{data='...'}` 防止日志刷屏。
- 注意：Java 序列化对跨版本物品兼容性弱于 NBT 序列化；SweetPlayerMarket 用 **item-nbt-api 的 NBT Base64**，兼容性更好——集成时优先复用后者的 `ItemSerializerManager`。

### 4.3 `MailboxItem` / `Transaction` / `TransactionRecord`

```java
public record MailboxItem(UUID id, UUID playerId, MailboxItemType type,
    SerializedItem item, double amount, String reason,
    UUID relatedAuctionId, long createdAt, long expiresAt, boolean claimed) { ... }

public record Transaction(UUID id, UUID auctionId, String actionType,
    UUID actorUuid, UUID sellerUuid, Double amount, String details,
    SerializedItem itemSnapshot, long timestamp) {
    // 兼容方法：status()==actionType, buyerUuid()==actorUuid, finalPrice()==amount
}

public record TransactionRecord(UUID id, UUID auctionId, String actionType,
    UUID actorUuid, Double amount, String details, long timestamp) {}
```

### 4.4 枚举

```java
public enum AuctionStatus { ACTIVE, FINISHED, CANCELLED, EXPIRED }

public enum AuctionCategory { ALL, WEAPONS, ARMOR, BLOCKS, MISC;   // matches(String itemType) 按物品名关键字归类
    static AuctionCategory getCategoryForItemType(String itemType); }

public enum SortOrder { TIME_LEFT, PRICE_ASC, PRICE_DESC, NEWEST;  // next() 循环切换
    String getDisplayName(); }

public enum MailboxItemType { ITEM, MONEY }
```

---

## 5. 经济系统交互

### 5.1 抽象层

```java
public interface EconomyService {
    String getName();
    CompletableFuture<Boolean> has(UUID player, double amount);
    CompletableFuture<Boolean> withdraw(UUID player, double amount, String reason);
    CompletableFuture<Void>     deposit(UUID player, double amount, String reason);
    String format(double amount);
    CompletableFuture<Double>   getBalance(UUID player);
}
```
全部异步（`CompletableFuture`），由 `FoliaCompat.runAsync` 包装 Vault 调用。

### 5.2 路由策略（EconomyRouter）

```
config: economy.preferred (KARTAEMERALDCURRENCY|VAULT)
    ├─ preferred=KEC 且启用 → KartaEmeraldEconomyService
    ├─ 否则 Vault 存在     → VaultEconomyService
    ├─ 否则 KEC 启用       → 回退 KEC
    └─ 都无               → 禁用插件（onEnable 里 disablePlugin）
```
Vault 通过 `getServer().getServicesManager().getRegistration(Economy.class)` 获取 provider。

### 5.3 资金流向（关键业务规则）

| 场景 | 流向 | 公式 |
|---|---|---|
| 一口价成交 | 买家 → 卖家 | 卖家得 `price × (1 − tax/100)`，`tax-percentage` 默认 5% |
| 手续费 | 从卖家所得中扣除 | 无独立托管账户（`economy.sink` 配置已预留 NONE/VAULT_ACCOUNT 但未实现） |
| 取消/流拍 | — | 物品退卖家，无资金变动 |
| 购买失败回滚 | 退款买家 + 追回卖家款 | 见 3.2 |

> 集成扩展点：竞拍场景需要新增"出价冻结→成交后最高出价者付款、次高出价者退款"的流程，并支持每口加价（bidIncrement）、自动延期（auto-extend）时按出价推进 endAt。

---

## 6. GUI 界面设计

### 6.1 基类体系

- **`Gui`（抽象，implements InventoryHolder, Listener）**：
  - 抽象方法 `getTitle() / getSize() / build() / onClick(event)`。
  - `setAsync(true)` 时 `open()` 只创建 Inventory + 注册监听，不自动 `openInventory`，由子类在异步数据就绪后调用受保护的 `openInventory()`（内部走 `FoliaCompat.runEntity`）。
  - 点击事件：`event.setCancelled(true)` + 校验 `getHolder()==this` + 校验玩家 UUID；关闭时 `HandlerList.unregisterAll(this)` 卸载监听（**每实例注册监听，开多少个 GUI 就注册多少个 Listener**，量小可接受）。
  - `createPlayerInfoItem()`：异步取余额，生成 PLAYER_HEAD 玩家信息头。
- **`PaginatedGui extends Gui`**：固定 54 格（6 行×9 列）；`addControlBar()` 画边框（`gui.border.*` 配置）+ 上一页(46)/下一页(52)/玩家信息(49)；`handleControlBarClick()` 处理翻页；抽象 `openPage(newPage)`。
- **`GuiItemBuilder`**：链式 ItemStack 构建器，`setName(Component/String) / setLore(String.../List/List<Component>/MiniMessage) / setSkullOwner / setAmount`，内部统一 `MessageParser.parse`。

### 6.2 界面布局（MainAuctionGui，54 格）

```
Row0  ( 0- 8): 边框
Row1-4( 9-43): 商品区 28 格: 10-16, 19-25, 28-34, 37-43（每行 7 个 × 4 行）
Row5  (45-53): 控制栏:
  45 边框 | 46 上一页 | 47 排序 | 48 (预留) | 49 玩家信息
  | 50 我的拍卖 | 51 邮箱 | 52 下一页 | 53 边框
```
- 异步构建：`getActiveAuctions(page, itemsPerPage, ALL, sort)` + `getBalance` + `getTotalActiveAuctionCount` 三个 future `thenCombine` 组合，数据齐后回实体线程填充 `inventory.setItem(slot, item)` 再 `openInventory()`。
- `createAuctionItem(auction, balance)`：物品显示名（原显示名或格式化材质名）+ lore 模板（messages.yml `gui.item-lore` 字符串列表，`PlaceholderContext` 注入 `%seller% %item_name% %quantity% %starting_price% %current_bid% %buy_now_price% %reserve_price% %bid_count% %highest_bidder% %time_left% %time_color% %duration% %listed_date% %status% %status_color% %price% %affordable_text% %needed_amount%`）+ 操作提示行（自己的→"管理"，买得起→"点击购买"，买不起→"余额不足"）。
- 点击行为：翻页/排序（循环 `sortOrder.next()`）/我的拍卖/邮箱/购买（先查余额再 `buyItem`，无论成败重开 GUI 刷新）。

### 6.3 其余界面

| GUI | 数据源 | 交互 |
|---|---|---|
| MyListingsGui | `getPlayerAuctions(uuid, page, limit)` | 点击 ACTIVE 拍卖 → 确认取消；槽 46 返回主界面、50 历史 |
| MailboxGui | `getUnclaimedItems(uuid, page, limit)` | ITEM 型显示原物品、MONEY 型显示金锭；点击单个领取，50 槽"全部领取" |
| HistoryGui | `getHistory(uuid, page, limit)`（Transaction 列表） | 展示 SOLD/EXPIRED/CANCELLED 状态、买卖双方、金额、时间；点击看详情 |

---

## 7. 命令系统

框架：**cloud（org.incendo.cloud）LegacyPaperCommandManager**，启动时探测 Brigadier 能力并注册（Brigadier → 原生 Tab 补全）。

### 7.1 `/ah` 命令树（别名 /auction /auctionhouse）

| 命令 | 权限 | 说明 |
|---|---|---|
| `/ah` | playerauctions.use | 打开主 GUI |
| `/ah help` | playerauctions.use | 帮助 |
| `/ah sell <price> [buyNow] [duration]` | playerauctions.sell | 手持物品上架；校验最低价/时长/上架数量上限（`auction.max-auctions-per-player`）；成功后广播 |
| `/ah listings` = `/ah myauctions` | playerauctions.use | 我的拍卖 GUI |
| `/ah search <keyword>` | playerauctions.search | 搜索（当前仅打开主 GUI） |
| `/ah notify <on\|off>` | playerauctions.notify | 通知开关 |
| `/ah history [player]` | playerauctions.history / .others | 交易历史 |
| `/ah reload` | playerauctions.reload | 重载配置 |

### 7.2 `/ahadmin` 命令树

| 命令 | 权限 | 说明 |
|---|---|---|
| `/ahadmin debug <on\|off>` | playerauctions.admin | 调试模式（内存 Set<UUID>） |

### 7.3 权限表（plugin.yml）

`playerauctions.use/sell/cancel/reload/admin/categories/notify/history/history.others`，`admin` 为 `reload+categories+notify+history+history.others` 的父权限。sell/cancel/use 默认 true，其余默认 op。

---

## 8. 配置文件全解析

### 8.1 config.yml

```yaml
auction:
  max-auctions-per-player: 5      # 单玩家上架上限
  auction-duration: 48h           # 默认时长（注：实际读取 defaults.duration）
  defaults:
    duration: 48h
  min-price: 1.0                  # 最低起拍价
  tax-percentage: 5               # 成交手续费比例 %
  notification-methods: [chat, actionbar, title, sound]
  broadcast:
    enabled: true
    on-listing: true              # 上架广播
    on-purchase: true             # 成交广播
    range: GLOBAL                 # GLOBAL | WORLD | NONE
economy:
  preferred: VAULT                # KARTAEMERALDCURRENCY | VAULT
  fallback: VAULT
  sink:
    mode: NONE                    # 手续费去向（预留）
    target: server_treasury
mailbox:
  enabled: true
  retention-days: 30              # 邮箱保留天数
gui:
  system: INVENTORY_FRAMEWORK     # 声明用 IF，实际代码用自定义 GUI
  size: 54
  items-per-page: 45
  border:
    enabled: true
    material: BLACK_STAINED_GLASS_PANE
    name: " "
    lore: []
# 数据库：JSON 文件（auctions.json/transactions.json/mailbox.json）
```

### 8.2 messages.yml（278 行，结构）

```
prefix / auction.* / broadcast.* / errors.*(约 20 条) / info.* / admin.*
/ mailbox.* / gui.*(main-title, my-listings-title, mailbox-title, history-title,
control-items.{previous-page,next-page,back,sort,my-listings,mailbox},
item-lore 模板, item-action.{own-auction,can-purchase,insufficient-funds})
```

---

## 9. 关键类与方法索引

| 层 | 类 | 关键方法 |
|---|---|---|
| 入口 | `PlayerAuction` | `onEnable/onDisable`，`getAuctionService/getEconomyRouter/getBroadcastManager/...` |
| 业务 | `AuctionService` | `createListing/buyItem/cancelAuction/processExpiredAuctions/getActiveAuctions/getPlayerAuctions/getPlayerHistory/getPlayerActiveAuctionCount/getTotalActiveAuctionCount` |
| 业务 | `AuctionExpirer` | `runTaskTimerAsynchronously(plugin, delay, period)` |
| 经济 | `EconomyRouter` | `setupEconomy/getService/hasService` |
| 经济 | `VaultEconomyService` | `has/withdraw/deposit/format/getBalance`（异步包装） |
| 存储 | `AuctionStorage` | 见 3.3 |
| 存储 | `StorageFactory` | `createAuctionStorage/createTransactionStorage` |
| 邮箱 | `MailboxService` | `addReturnedItem/addMoney/getUnclaimedItems/getUnclaimedCount/claimItem/claimAll/cleanupExpiredItems` |
| GUI | `Gui/PaginatedGui` | `open/setAsync/openInventory/createPlayerInfoItem/addControlBar/handleControlBarClick` |
| GUI | `GuiItemBuilder` | `setName/setLore/setLoreComponents/setSkullOwner/build` |
| 消息 | `ConfigManager` | `getMessage/getPrefixedMessage/processMessageAsComponent/sendPrefixedMessage` |
| 消息 | `MessageParser` | `parse/parseToLegacy/parseMiniMessage/parseHexAndRgb/parseLegacy/toPlainText` |
| 工具 | `DurationParser` | `parse("1d12h") → Optional<Long> ms` |
| 工具 | `TimeUtil` | `formatDuration(ms) → "2d 3h 4m 5s"` |
| 工具 | `PlaceholderContext` | `addPlaceholder/applyTo` |
| 工具 | `PlayerNameCache` | `getName(uuid) → CompletableFuture<String>`（Guava Cache 1h） |
| 兼容 | `FoliaCompat` | `runTask/runTaskLater/runTaskTimer/runEntity/runEntityLater/runRegion/runAsync/runAsyncTimer` |

---

## 10. 现有实现缺口（集成时需要补全）

1. **竞拍核心缺失**：只有一口价直购；`bid_count/highest_bidder` 是占位符。→ XMPlayerMarketPlus 需实现出价/加价/最高价/自动延期。
2. **创建拍卖 GUI 缺失**：`MyListingsGui` 槽 51 提示 "Create auction feature is currently unavailable"。→ 需补 `GuiCreateAuction`。
3. **SQLite 存储未启用**：`StorageFactory` 硬编码 JSON；SQLite 类已写但无开关。→ 集成到 SweetPlayerMarket 后直接用其 HikariCP 数据库（SQLite/MySQL），天然解决。
4. **搜索未接入**：`/ah search` 只是打开主 GUI；`countActiveAuctions(category,sort,search)` 已实现但 GUI 未用。→ 复用 SweetPlayerMarket 的 `Searching`/关键词搜索。
5. **邮件系统独立文件**：mailbox.json 与拍卖数据分离，无"邮件打开入口"除 GUI 按钮。→ 集成时并入现有"我的商品"领取体系。
6. **手续费 sink 未实现**：`economy.sink` 配置存在但资金没收。→ XMPlayerMarketPlus 可将手续费（上架费/成交税）转入指定账户或保留在服务器经济。
7. **无 NPC/物品点击打开入口**：仅命令打开 GUI。→ 集成时新增右键拍卖令牌/书本、NPC 挂钩（Citizens）打开入口。
8. **消息硬编码**：部分 GUI lore/状态文本硬编码在 Java 中（`<#2ECC71>` 等），未完全走 messages.yml。→ 新模块全部走 `AuctionMessages`（LanguageManager）。
