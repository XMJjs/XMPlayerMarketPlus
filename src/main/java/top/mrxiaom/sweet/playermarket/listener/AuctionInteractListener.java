package top.mrxiaom.sweet.playermarket.listener;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import top.mrxiaom.pluginbase.func.AutoRegister;
import top.mrxiaom.sweet.playermarket.SweetPlayerMarket;
import top.mrxiaom.sweet.playermarket.auction.AuctionConfig;
import top.mrxiaom.sweet.playermarket.auction.AuctionMessages;
import top.mrxiaom.sweet.playermarket.func.AbstractModule;
import top.mrxiaom.sweet.playermarket.gui.auction.GuiAuctionMain;

import java.util.List;

/**
 * 拍卖打开入口监听器：
 * <ul>
 *   <li><b>拍卖令牌右键</b>：手持带 PDC 标记的令牌（默认 PAPER + 名称）右键 → 打开拍卖主菜单。</li>
 *   <li><b>NPC 右键</b>：软依赖 Citizens，右键 NPC → 打开拍卖主菜单。</li>
 * </ul>
 */
@AutoRegister
public class AuctionInteractListener extends AbstractModule implements Listener {
    private static final String TOKEN_KEY = "xm_auction_token";
    private static final String TOKEN_NAME = "§6§l拍卖行令牌";

    public AuctionInteractListener(SweetPlayerMarket plugin) {
        super(plugin);
        // 显式注册监听器（与框架 Prompter 注册方式一致，确保事件生效；
        // 若宿主框架对 @AutoRegister 模块已自动注册 Listener，可移除下一行避免重复触发）
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    private boolean isToken(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        if (!item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        return pdc.has(new NamespacedKey(plugin, TOKEN_KEY), PersistentDataType.BYTE);
    }

    /**
     * 给予玩家拍卖令牌（右键打开拍卖行）。
     */
    public static void giveToken(Player player) {
        SweetPlayerMarket plugin = SweetPlayerMarket.getInstance();
        ItemStack token = new ItemStack(Material.getMaterial(AuctionConfig.inst().tokenMaterial()) != null
                ? Material.getMaterial(AuctionConfig.inst().tokenMaterial())
                : Material.PAPER);
        ItemMeta meta = token.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(TOKEN_NAME);
            meta.setLore(java.util.Arrays.asList(
                    "§7右键打开拍卖行",
                    "§7拍卖、竞拍、一口价都在这里!"));
            meta.getPersistentDataContainer().set(new NamespacedKey(plugin, TOKEN_KEY), PersistentDataType.BYTE, (byte) 1);
            token.setItemMeta(meta);
        }
        player.getInventory().addItem(token);
        AuctionMessages.token__given.tm(player);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission("sweet.playermarket.auction")) {
            return;
        }
        // 1. 拍卖令牌右键
        if (AuctionConfig.inst().entranceToken() && isToken(event.getItem())) {
            event.setCancelled(true);
            GuiAuctionMain.open(player);
            return;
        }
        // 2. NPC 右键（Citizens 软依赖）
        if (AuctionConfig.inst().entranceNpc() && event.getClickedEntity() != null) {
            if (isCitizensNpc(event.getClickedEntity())) {
                event.setCancelled(true);
                GuiAuctionMain.open(player);
            }
        }
    }

    private static Boolean citizensAvailable = null;

    private boolean isCitizensNpc(@NotNull Entity entity) {
        if (citizensAvailable == null) {
            try {
                Class.forName("net.citizensnpcs.api.CitizensAPI");
                citizensAvailable = true;
            } catch (ClassNotFoundException | LinkageError e) {
                citizensAvailable = false;
            }
        }
        if (!citizensAvailable) return false;
        try {
            return net.citizensnpcs.api.CitizensAPI.getNPCRegistry().isNPC(entity);
        } catch (Throwable e) {
            return false;
        }
    }
}
