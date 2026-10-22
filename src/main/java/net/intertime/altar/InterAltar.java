package net.intertime.altar;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class InterAltar extends JavaPlugin {

    static final char S = '\u00a7';
    private static final Pattern HEX = Pattern.compile("&#([0-9a-fA-F]{6})");

    static class LootEntry {
        final Material mat;
        final int min, max, weight;

        LootEntry(Material mat, int min, int max, int weight) {
            this.mat = mat;
            this.min = min;
            this.max = max;
            this.weight = weight;
        }
    }

    final List<Altar> altars = new ArrayList<Altar>();
    final Random rnd = new Random();
    NamespacedKey itemKey;
    private final List<LootEntry> loot = new ArrayList<LootEntry>();
    private BukkitTask ticker;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        itemKey = new NamespacedKey(this, "acid_altar");
        loadLoot();
        getServer().getPluginManager().registerEvents(new AltarListener(this), this);
        ticker = Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                for (Altar a : new ArrayList<Altar>(altars)) {
                    try {
                        a.tick();
                    } catch (Throwable t) {
                        getLogger().warning("Помилка вівтаря: " + t);
                        t.printStackTrace();
                        a.forceRemove();
                    }
                    if (a.finished) altars.remove(a);
                }
            }
        }, 1L, 1L);
        getLogger().info("InterAltar увімкнено!");
    }

    @Override
    public void onDisable() {
        if (ticker != null) ticker.cancel();
        // повертаємо місцевість, щоб після рестарту не лишилось залишків
        for (Altar a : new ArrayList<Altar>(altars)) a.forceRemove();
        altars.clear();
    }

    // ================================================================ текст

    static String hex(String h) {
        StringBuilder sb = new StringBuilder().append(S).append('x');
        for (int i = 0; i < 6; i++) sb.append(S).append(h.charAt(i));
        return sb.toString();
    }

    static String color(String s) {
        if (s == null) return "";
        Matcher m = HEX.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(hex(m.group(1))));
        m.appendTail(sb);
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }

    /** msg("key", "name", "value", ...) - підставляє %name% і розфарбовує. */
    String msg(String key, String... kv) {
        String s = getConfig().getString("messages." + key, "&c[немає повідомлення " + key + "]");
        for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace("%" + kv[i] + "%", kv[i + 1]);
        return color(s);
    }

    // ================================================================ предмет

    ItemStack createItem(int amount) {
        ItemStack it = new ItemStack(Material.ENDER_CHEST, amount);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(color(getConfig().getString("item.name", "&aКислотний Вівтар")));
        List<String> lore = new ArrayList<String>();
        for (String l : getConfig().getStringList("item.lore")) lore.add(color(l));
        m.setLore(lore);
        m.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    boolean isAltarItem(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    // ================================================================ лут

    void loadLoot() {
        loot.clear();
        for (Map<?, ?> m : getConfig().getMapList("loot.items")) {
            Material mat = Material.matchMaterial(String.valueOf(m.get("item")));
            if (mat == null) {
                getLogger().warning("Невідомий предмет у луті: " + m.get("item"));
                continue;
            }
            loot.add(new LootEntry(mat, num(m.get("min"), 1), num(m.get("max"), 1), Math.max(1, num(m.get("weight"), 1))));
        }
    }

    private static int num(Object o, int def) {
        return o instanceof Number ? ((Number) o).intValue() : def;
    }

    /** Лут для однієї скрині: 27 слотів, частина заповнена випадково. */
    ItemStack[] rollLoot() {
        ItemStack[] out = new ItemStack[27];
        if (loot.isEmpty()) return out;
        List<Integer> per = getConfig().getIntegerList("loot.per-chest");
        int lo = per.size() > 0 ? per.get(0) : 5;
        int hi = per.size() > 1 ? per.get(1) : 9;
        int n = lo + rnd.nextInt(Math.max(1, hi - lo + 1));
        List<Integer> slots = new ArrayList<Integer>();
        for (int i = 0; i < 27; i++) slots.add(i);
        Collections.shuffle(slots, rnd);
        int total = 0;
        for (LootEntry e : loot) total += e.weight;
        for (int i = 0; i < Math.min(n, 27); i++) {
            int r = rnd.nextInt(total);
            LootEntry pick = loot.get(0);
            for (LootEntry e : loot) {
                r -= e.weight;
                if (r < 0) {
                    pick = e;
                    break;
                }
            }
            int amt = pick.min + rnd.nextInt(Math.max(1, pick.max - pick.min + 1));
            out[slots.get(i)] = new ItemStack(pick.mat, Math.max(1, Math.min(amt, pick.mat.getMaxStackSize())));
        }
        return out;
    }

    // ================================================================ спавн

    /** Повертає текст помилки або null, якщо місце підходить. */
    String validate(Location p) {
        World w = p.getWorld();
        if (w.getEnvironment() != World.Environment.NORMAL) return msg("err-world");
        int cx = p.getBlockX(), cy = p.getBlockY(), cz = p.getBlockZ();
        if (cy < 12 || cy + 8 >= w.getMaxHeight()) return msg("err-space");
        double minD = getConfig().getDouble("altar.min-distance", 40);
        for (Altar a : altars) {
            if (!a.world.equals(w)) continue;
            double dx = a.cx - cx, dz = a.cz - cz;
            if (dx * dx + dz * dz < minD * minD) return msg("err-near");
        }
        int maxClear = getConfig().getInt("altar.max-clear-blocks", 80);
        int solid = 0;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                int x = cx + dx, z = cz + dz;
                // небо має бути відкрите над усім майданчиком (трава й малі горби до +2 блоків не заважають)
                if (w.getHighestBlockYAt(x, z) > cy + 2) return msg("err-sky");
                for (int y = cy - 3; y <= cy + 5; y++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (b.getState() instanceof InventoryHolder) return msg("err-container");
                    if (y >= cy && b.getType().isSolid()) solid++;
                }
            }
        }
        if (solid > maxClear) return msg("err-space");
        return null;
    }

    void spawn(Player p, Location loc) {
        Altar a = new Altar(this, p.getName(), loc);
        altars.add(a);
        Bukkit.broadcastMessage(msg("spawn", "player", p.getName(),
                "x", String.valueOf(a.cx), "y", String.valueOf(a.cy), "z", String.valueOf(a.cz)));
    }

    Altar altarAtChest(Block b) {
        for (Altar a : altars) {
            if (a.isChest(b)) return a;
        }
        return null;
    }

    Altar altarContaining(Block b) {
        for (Altar a : altars) {
            if (a.contains(b)) return a;
        }
        return null;
    }

    // ================================================================ команди

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("interaltar.admin")) {
            s.sendMessage(msg("no-perm"));
            return true;
        }
        String sub = a.length == 0 ? "help" : a[0].toLowerCase();
        if (sub.equals("give")) {
            Player t = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : (s instanceof Player ? (Player) s : null);
            if (t == null) {
                s.sendMessage("Гравця не знайдено. Використання: /acidaltar give <гравець> [кількість]");
                return true;
            }
            int amount = 1;
            if (a.length > 2) {
                try {
                    amount = Math.max(1, Math.min(64, Integer.parseInt(a[2])));
                } catch (NumberFormatException ignored) {
                }
            }
            HashMap<Integer, ItemStack> left = t.getInventory().addItem(createItem(amount));
            for (ItemStack it : left.values()) t.getWorld().dropItemNaturally(t.getLocation(), it);
            s.sendMessage(msg("given", "amount", String.valueOf(amount), "player", t.getName()));
        } else if (sub.equals("clear")) {
            for (Altar al : new ArrayList<Altar>(altars)) al.forceRemove();
            altars.clear();
            s.sendMessage("Усі вівтарі прибрано.");
        } else if (sub.equals("reload")) {
            reloadConfig();
            loadLoot();
            s.sendMessage("InterAltar: конфіг перезавантажено.");
        } else {
            s.sendMessage("/acidaltar give [гравець] [кількість] | clear | reload");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<String>();
        if (a.length == 1) {
            for (String x : Arrays.asList("give", "clear", "reload")) {
                if (x.startsWith(a[0].toLowerCase())) out.add(x);
            }
        } else if (a.length == 2 && a[0].equalsIgnoreCase("give")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(a[1].toLowerCase())) out.add(p.getName());
            }
        }
        return out;
    }
}
