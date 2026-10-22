package net.intertime.altar;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.block.BlockState;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.block.Block;

import java.util.Iterator;

public class AltarListener implements Listener {

    private final InterAltar pl;

    public AltarListener(InterAltar pl) {
        this.pl = pl;
    }

    /** Постановка ендер-сундука з мітки = старт івенту. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (pl.isAltarItem(e.getItemInHand())) {
            e.setCancelled(true);
            if (!p.hasPermission("interaltar.use")) {
                p.sendMessage(pl.msg("no-perm"));
                return;
            }
            Location loc = e.getBlockPlaced().getLocation();
            String err = pl.validate(loc);
            if (err != null) {
                p.sendMessage(err);
                return;
            }
            consume(p, e.getHand());
            pl.spawn(p, loc);
            return;
        }
        // не даємо будувати всередині вівтаря
        if (pl.altarContaining(e.getBlockPlaced()) != null) e.setCancelled(true);
    }

    private void consume(Player p, EquipmentSlot hand) {
        if (p.getGameMode() == GameMode.CREATIVE) return;
        boolean off = hand == EquipmentSlot.OFF_HAND;
        ItemStack it = off ? p.getInventory().getItemInOffHand() : p.getInventory().getItemInMainHand();
        if (it == null) return;
        if (it.getAmount() <= 1) {
            if (off) p.getInventory().setItemInOffHand(null);
            else p.getInventory().setItemInMainHand(null);
        } else {
            it.setAmount(it.getAmount() - 1);
        }
    }

    /** Скрині вівтаря: до відкриття лута клік = активація / повідомлення. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        Altar a = pl.altarAtChest(e.getClickedBlock());
        if (a == null) return;
        // з початку появи лута скрині можна відкривати (дивитись, як з'являються барвник і лут)
        if (a.state == Altar.State.OPEN || a.state == Altar.State.REVEAL) return;
        e.setCancelled(true);
        if (e.getHand() == EquipmentSlot.HAND) a.click(e.getPlayer());
    }

    private Altar altarOf(Inventory inv) {
        InventoryHolder h = inv.getHolder();
        return h instanceof BlockState ? pl.altarAtChest(((BlockState) h).getBlock()) : null;
    }

    /** Поки йде поява барвника/луту, зі скринь нічого не можна брати. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInvClick(InventoryClickEvent e) {
        Altar a = altarOf(e.getInventory());
        if (a != null && a.state == Altar.State.REVEAL) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInvDrag(InventoryDragEvent e) {
        Altar a = altarOf(e.getInventory());
        if (a != null && a.state == Altar.State.REVEAL) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (pl.altarContaining(e.getBlock()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        strip(e.blockList().iterator());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        strip(e.blockList().iterator());
    }

    private void strip(Iterator<Block> it) {
        while (it.hasNext()) {
            if (pl.altarContaining(it.next()) != null) it.remove();
        }
    }
}
