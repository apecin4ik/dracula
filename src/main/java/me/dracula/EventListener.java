package me.dracula;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;

public class EventListener implements Listener {

    private final DraculaPlugin plugin;

    public EventListener(DraculaPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean inZone(Location l) {
        Location b = plugin.getBase();
        if (b == null || l.getWorld() != b.getWorld()) return false;
        double dx = l.getX() - b.getX(), dz = l.getZ() - b.getZ();
        int r = plugin.protectRadius();
        return dx * dx + dz * dz <= (double) r * r;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!e.getPlayer().hasPermission("dracula.admin") && inZone(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!e.getPlayer().hasPermission("dracula.admin") && inZone(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> inZone(b.getLocation()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> inZone(b.getLocation()));
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        Location chest = plugin.getChestLoc();
        if (chest == null || !e.getClickedBlock().getLocation().equals(chest)) return;

        e.setCancelled(true);
        Player p = e.getPlayer();
        if (plugin.getState() == DraculaPlugin.State.LOCKED) {
            p.sendMessage(plugin.msg("messages.locked-click").replace("{time}", DraculaPlugin.fmt(plugin.getLeft())));
        } else if (plugin.getState() == DraculaPlugin.State.OPEN && plugin.getLootInv() != null) {
            p.openInventory(plugin.getLootInv());
        }
    }
}
