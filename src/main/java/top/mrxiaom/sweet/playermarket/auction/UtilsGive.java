package top.mrxiaom.sweet.playermarket.auction;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * 背包发放工具：尽力发放，返回成功发放的物品件数（背包满时剩余不发）。
 */
public final class UtilsGive {
    private UtilsGive() {
    }

    /**
     * 给玩家发放一批物品。
     *
     * @return 成功放入背包的物品件数（每个 ItemStack 计 1 件，内部按可叠放性合并）
     */
    public static int giveItems(Player player, List<ItemStack> items) {
        int given = 0;
        for (ItemStack item : items) {
            if (item == null) continue;
            java.util.HashMap<Integer, ItemStack> left = player.getInventory().addItem(item.clone());
            if (left.isEmpty()) {
                given++;
            } else {
                // 背包满，剩余物品原地掉落
                for (ItemStack rest : left.values()) {
                    player.getWorld().dropItem(player.getLocation(), rest);
                }
            }
        }
        return given;
    }
}
