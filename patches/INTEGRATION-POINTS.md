# XMPlayerMarketPlus 接入点修改清单

> 拍卖模块的**全部新增代码**位于 `src/main/java/.../auction`、`gui/auction`、`actions`（Auction*）、
> `commands/arguments/AuctionArguments`、`listener/AuctionInteractListener` 与 `src/main/resources`
> 下的 `auction.yml`、`gui/auction-*.yml`，复制到 SweetPlayerMarket 工程即可。
> 对既有文件的修改仅 3 处 + 1 处可选，如下。

---

## 修改 1：`SweetPlayerMarket.java`（主类 beforeEnable）

在 `beforeEnable()` 中追加 3 行（依赖库注册 / 动作注册 / 消息注册）：

```java
@Override
protected void beforeEnable() {
    // ……原有代码……

    // ✚ ① 注册拍卖数据库（复用 HikariCP 连接池，建表由 reload 完成）
    // 注意：registerDatabase 是否支持变参取决于框架版本，这里分两次调用以保证兼容
    options.registerDatabase(
            this.marketplaceDatabase = new MarketplaceDatabase(this),
            this.tradeLogsDatabase = new TradeLogsDatabase(this)
    );
    options.registerDatabase(
            this.auctionDatabase = new top.mrxiaom.sweet.playermarket.auction.AuctionDatabase(this)   // ✚
    );

    // ……原有 LanguageManager 链……

    LanguageManager.inst()
            .setLangFile("messages.yml")
            .register(Messages.class)
            // ……原有 register 链……
            .register(top.mrxiaom.sweet.playermarket.auction.AuctionMessages.class)   // ✚
            .reload();

    // ……原有 initEconomy()……

    // ✚ ② 注册拍卖动作
    ActionProviders.registerActionProviders(
            // ……原有 PROVIDER 链……
            top.mrxiaom.sweet.playermarket.actions.ActionAuctionOpen.PROVIDER,
            top.mrxiaom.sweet.playermarket.actions.ActionAuctionPage.PROVIDER,
            top.mrxiaom.sweet.playermarket.actions.ActionAuctionSort.PROVIDER,
            top.mrxiaom.sweet.playermarket.actions.ActionAuctionBid.PROVIDER,
            top.mrxiaom.sweet.playermarket.actions.ActionAuctionBuyNow.PROVIDER,
            top.mrxiaom.sweet.playermarket.actions.ActionAuctionCancel.PROVIDER,
            top.mrxiaom.sweet.playermarket.actions.ActionAuctionClaim.PROVIDER
    );
}
```

并新增字段（可选，用于调试访问）：

```java
private AuctionDatabase auctionDatabase;
public AuctionDatabase getAuctionDatabase() { return auctionDatabase; }
```

> 说明：`AuctionService / AuctionConfig / AuctionExpireTask / GuiAuction* / AuctionInteractListener`
> 均为 `@AutoRegister`，由框架自动扫描实例化，**无需手动 new**；
> 模块初始化顺序由 `priority()` 保证（默认 1000，数据库先于业务逻辑）。

---

## 修改 2：`commands/CommandMain.java`（/spm auction 子命令）

在 `onCommand` 中 `open` 分支之前插入：

```java
if (command.match("auction")) {
    if (!sender.hasPermission("sweet.playermarket.auction")) {
        return Messages.Command.no_permission.tm(sender);
    }
    return command.to(top.mrxiaom.sweet.playermarket.commands.arguments.AuctionArguments::of)
            .execute(plugin, sender);
}
```

在 `onTabComplete` 的 `args.length == 1` 分支追加：

```java
add(sender, list, "sweet.playermarket.auction", "auction");
```

---

## 修改 3：`plugin.yml`（权限声明）

在文件末尾追加：

```yaml
permissions:
  sweet.playermarket.auction:
    description: 允许使用拍卖功能
    default: true
  sweet.playermarket.auction.admin:
    description: 拍卖管理（重载配置等）
    default: op
```

---

## 修改 4（可选）：`gui/marketplace.yml` 增加拍卖入口按钮

在 `other-icons` 中追加（让全球市场界面也能进入拍卖行）：

```yaml
  拍:
    material: GOLD_BLOCK
    display: '&6&l拍卖行'
    lore:
      - ''
      - '&7进入拍卖行：竞拍、一口价、自动延期'
      - ''
      - '&a左键 &7| &f打开拍卖行'
    left-click-commands:
      - '[auction-open:main]'
```

并在 `inventory` 布局中把底部某一行字符替换为 `拍`。

---

## 无需修改的部分

| 文件 | 原因 |
|---|---|
| `build.gradle.kts` | 新代码位于既有包路径下，无新依赖 |
| `database.yml` | 复用现有连接池，`{prefix}auctions` / `{prefix}auction_bids` 表自动创建 |
| `messages.yml` | 拍卖消息经 `AuctionMessages`（LanguageManager）自动生成 |
| `config.yml` | 拍卖配置独立于 `auction.yml` |

---

## 编译与运行自检清单

1. `gradlew build`（或 `gradlew shadowJar`）成功；
2. 启动服务器无 `NoClassDefFoundError` / `AbstractMethodError`（插件库已 shade）；
3. 首次加载日志出现 `auctions` / `auction_bids` 建表（SQLite 或 MySQL）；
4. `/spm auction` 能打开主菜单；
5. 创建拍卖 → 列表可见 → 出价/一口价/取消/到期各路径消息正常；
6. 断线重连后待领区（claim.*）内容可正常领取。
