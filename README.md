# XMPlayerMarketPlus

> **SweetPlayerMarket × PlayerAuctions 完美集成版** —— 全球市场 + 拍卖行二合一

[![build](https://github.com/XMJjs/XMPlayerMarketPlus/actions/workflows/build.yml/badge.svg)](https://github.com/XMJjs/XMPlayerMarketPlus/actions/workflows/build.yml)
![Java](https://img.shields.io/badge/Java-8%2B-orange)
![Minecraft](https://img.shields.io/badge/Minecraft-1.13%2B-blueviolet)
[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL--3.0-blue.svg)](LICENSE)

在 [SweetPlayerMarket](https://github.com/MrXiaoM/SweetPlayerMarket)（全球市场）基础设施之上，完整移植 [PlayerAuctions](https://github.com/MinekartaStudio/PlayerAuctions) 的拍卖能力：**拍卖创建 / 竞拍 / 一口价 / 自动延期 / 取消上架 / 手续费结算 / 待领（邮箱）领取**，并补齐全部 GUI 界面与打开入口。

## ✨ 功能特性

| 能力 | 说明 |
|---|---|
| 全球市场 | 原 SweetPlayerMarket 全部功能：买卖商店、多币种、标签、搜索、自动上架、跨服同步 |
| 拍卖创建 | 手持物品 + 起拍价/一口价/时长/自动延期（GUI 向导 或 `/spm auction sell` 命令） |
| 竞拍出价 | 最高出价 + 加价幅度 + "出价即冻结"（可配置为成交时扣款） |
| 一口价 | 立即成交，自动退还当前最高出价者押金 |
| 自动延期 | 截止前 N 分钟出价 → 自动顺延 N 分钟 |
| 取消上架 | 卖家取消，物品退还 + 最高出价者押金退还 |
| 手续费结算 | 上架费（可选）+ 成交税（默认 5%，从卖家所得扣除） |
| 待领补偿 | 离线/背包满时，成交物品/款项进入待领区，GUI 一键领取 |
| 到期处理 | 30s 定时任务：有出价→成交结算；无出价→流拍退物 |
| GUI | 拍卖主菜单 / 拍卖浏览(分页排序) / 拍卖详情 / 我的拍卖 / 竞拍记录 / 创建拍卖 |
| 打开入口 | `/spm auction` 命令、拍卖令牌右键、Citizens NPC 右键、市场 GUI 按钮 |

## 🚀 快速开始

### 环境要求

- **Java 8+**（服务端运行）；**JDK 25**（编译，见构建）
- **服务端版本支持矩阵**（目标：26.x 与 1.21.x 以上）：
  | 服务端 | 支持 | 说明 |
  |---|---|---|
  | Paper / Spigot 1.20.x | ✅ | 基准编译版本（spigot-api 1.20） |
  | Paper / Spigot **1.21.x 及以上** | ✅ | 完全支持 |
  | Paper **26.x** | ✅ | 代码零 NMS 引用、纯 Bukkit API，向后兼容加载 |
  | Folia / Canvas | ✅ | `folia-supported: true` + 调度器兼容层 |
  - `api-version: 1.13` 声明触发服务端兼容模式，向下/向上均安全
- **经济插件**（至少其一）：Vault（推荐）/ PlayerPoints / MPoints / CoinsEngine / 自定义货币

### 安装

1. 从 Releases 或 GitHub Actions 产物下载 `XMPlayerMarketPlus-*.jar`
2. 放入服务端 `plugins/` 目录
3. 重启服务器（首次启动自动生成 `config.yml`、`auction.yml`、`currencies.yml` 等配置文件）
4. 配置 `auction.yml` 中 `auction.currency` 指定拍卖货币（默认 `Vault`）
5. 玩家输入 `/spm auction` 体验拍卖行

## 📖 命令与权限

```
/spm auction                      → 拍卖主菜单
/spm auction list                 → 拍卖浏览
/spm auction my                   → 我的拍卖
/spm auction bids                 → 竞拍记录
/spm auction create [价格] [一口价] [时长]  → 创建拍卖（手持物品）
/spm auction sell <价格> [一口价] [时长]    → 命令式快速上架
/spm auction cancel <id>          → 取消拍卖
/spm auction token                → 获取拍卖令牌（右键打开）
/spm auction reload               → 重载拍卖配置（op）
```

| 权限 | 默认 |
|---|---|
| `sweet.playermarket.auction` | true（使用拍卖功能） |
| `sweet.playermarket.auction.admin` | op（拍卖管理） |
| `sweet.playermarket.*` | 全球市场原有权限体系不变 |

## 🔨 构建

### 方式一：GitHub Actions 云端编译（推荐）

1. 推送代码到 GitHub 仓库
2. 打开仓库 **Actions** 页面 → 选择 **build** 工作流 → 点击 **Run workflow**（手动触发）
3. 构建完成后在本次运行记录底部 **Artifacts** 下载 `XMPlayerMarketPlus` jar

### 方式二：本地编译

```bash
./gradlew build shadowJar
# 产物位于 out/XMPlayerMarketPlus-*.jar（或 build/libs/）
```

> 构建要求 JDK 25；产物兼容 Java 8+ 运行时。

## 📁 项目结构

```
├── .github/workflows/build.yml    # GitHub Actions 云端编译
├── src/main/java/.../
│   ├── auction/                   # 拍卖模块（模型/服务/数据库/定时任务/配置/消息）
│   ├── gui/auction/               # 6 个拍卖 GUI
│   ├── actions/                   # 拍卖动作（[auction-*]）
│   ├── commands/arguments/        # /spm auction 子命令
│   └── listener/                  # 令牌/NPC 打开入口
├── src/main/resources/
│   ├── auction.yml                # 拍卖配置
│   └── gui/auction-*.yml          # 拍卖 GUI 布局
└── docs/                          # 技术分析/设计/实施/审查文档
```

## 🧪 测试与审查

- `docs/01-PlayerAuctions深度技术分析.md` — 源插件全模块分析
- `docs/02-XMPlayerMarketPlus集成设计.md` — 架构/数据/经济/GUI/命令设计
- `docs/03-分阶段实施方案与测试清单.md` — 阶段实施 + 17 项功能测试矩阵
- `docs/04-代码审查报告.md` — 静态审查（华为 Java 规范 82+ 良好）
- `docs/05-模拟玩家逻辑验证报告.md` — 玩家视角逐步走查 + 并发/资金守恒验证

## 📄 开源协议

**开源协议跟随原项目（SweetPlayerMarket）**：本项目使用 [AGPL-3.0](LICENSE)（GNU Affero General Public License v3），与上游 SweetPlayerMarket 完全一致。

- 上游主项目：[MrXiaoM/SweetPlayerMarket](https://github.com/MrXiaoM/SweetPlayerMarket)（AGPL-3.0）—— 本仓库主体代码派生自该项目，LICENSE 文本与其逐字节一致
- 移植参考：[MinekartaStudio/PlayerAuctions](https://github.com/MinekartaStudio/PlayerAuctions)（GPL-3.0）—— 拍卖业务逻辑按相同设计独立重写实现；AGPL-3.0 为 GPL-3.0 的兼容超集（AGPL 义务 ≥ GPL），使用 AGPL 同时满足双方授权要求
- 使用本插件请遵守 AGPL-3.0 条款（保留版权声明、修改后需开源等）。

## 🙏 致谢

- [MrXiaoM](https://github.com/MrXiaoM) — SweetPlayerMarket 与 PluginBase 框架
- [MinekartaStudio](https://github.com/MinekartaStudio) — PlayerAuctions 拍卖业务设计
