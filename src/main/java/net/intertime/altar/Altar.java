package net.intertime.altar;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Кислотний вівтар 7x7: підлога вровень із землею (під краями добудовується фундамент),
 * басейн кислоти 3x3, 4 скрині, смарагдові стовпи по кутах.
 * Стани: BUILDING -> READY -> ACTIVATING -> REVEAL (барвник, потім лут) -> OPEN -> REMOVING.
 */
public class Altar {

    enum State {BUILDING, READY, ACTIVATING, REVEAL, OPEN, REMOVING}

    static class Part {
        final int x, y, z, group;
        final double sub;
        final Material mat;
        final BlockFace face;
        BlockData old;
        boolean placed;

        Part(int x, int y, int z, Material mat, BlockFace face, int group, double sub) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.mat = mat;
            this.face = face;
            this.group = group;
            this.sub = sub;
        }
    }

    // Групи будівництва і частка часу анімації на кожну:
    // 0 фундамент/розчистка, 1 дно басейну і стінки, 2 кислота, 3 підлога спіраллю, 4 стовпи, 5 скрині
    private static final double[] W = {0.10, 0.10, 0.12, 0.30, 0.23, 0.15};

    private static final Particle DUST = particle("REDSTONE", "DUST");
    private static final Particle HAPPY = particle("VILLAGER_HAPPY", "HAPPY_VILLAGER");

    private static Particle particle(String... names) {
        for (String n : names) {
            try {
                return Particle.valueOf(n);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    final InterAltar pl;
    final World world;
    final int cx, cy, cz;
    final String owner;
    State state = State.BUILDING;
    boolean finished;

    private final List<Part> parts = new ArrayList<Part>();
    private final List<Part> chests = new ArrayList<Part>();
    private final int[] groupSize = new int[W.length];
    private final Set<String> struct = new HashSet<String>();
    private final ItemStack[][] loot = new ItemStack[4][];
    private final List<Integer> orderDye = new ArrayList<Integer>();
    private final List<Integer> orderLoot = new ArrayList<Integer>();
    private final Random rnd = new Random();
    private final BossBar bar;

    private int tick, stateTick, placed, removeIdx, removeBase, revealIdx, revealPhase, secondsLeft;

    Altar(InterAltar pl, String owner, Location c) {
        this.pl = pl;
        this.owner = owner;
        this.world = c.getWorld();
        this.cx = c.getBlockX();
        this.cy = c.getBlockY();
        this.cz = c.getBlockZ();
        buildParts();
        for (int i = 0; i < 4; i++) loot[i] = pl.rollLoot();
        for (int i = 0; i < 27; i++) {
            orderDye.add(i);
            orderLoot.add(i);
        }
        Collections.shuffle(orderDye, rnd);
        Collections.shuffle(orderLoot, rnd);
        bar = Bukkit.createBossBar(InterAltar.color(pl.getConfig().getString("messages.bar-build", "")),
                BarColor.GREEN, BarStyle.SEGMENTED_10);
    }

    // ================================================================ структура

    private void add(int x, int y, int z, Material m, BlockFace f, int group, double sub) {
        Part p = new Part(x, y, z, m, f, group, sub);
        parts.add(p);
        struct.add(x + "," + y + "," + z);
        if (m == Material.CHEST) chests.add(p);
    }

    private void buildParts() {
        final int floor = cy - 1; // підлога вровень із землею
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                int x = cx + dx, z = cz + dz;
                int d = Math.max(Math.abs(dx), Math.abs(dz));
                double ang = Math.toDegrees(Math.atan2(dz, dx));
                if (ang < 0) ang += 360;
                if (d <= 1) {
                    add(x, floor - 2, z, Material.LIME_CONCRETE, null, 1, d);
                    add(x, floor - 1, z, Material.WATER, null, 2, d);
                    add(x, floor, z, Material.WATER, null, 2, d + 10);
                } else {
                    if (d == 2) add(x, floor - 1, z, Material.MOSSY_STONE_BRICKS, null, 1, 10 + ang / 360.0);
                    Material fl = d == 2
                            ? (((dx + dz) & 1) == 0 ? Material.LIME_CONCRETE : Material.MOSSY_STONE_BRICKS)
                            : Material.MOSSY_COBBLESTONE;
                    add(x, floor, z, fl, null, 3, d * 10 + ang / 36.0); // спіраль зсередини назовні
                    // фундамент: якщо під краєм порожнеча (схил, яма) - добудовуємо до землі
                    int startY = d == 2 ? floor - 2 : floor - 1;
                    for (int y = startY, n = 0; n < 8; y--, n++) {
                        if (world.getBlockAt(x, y, z).getType().isSolid()) break;
                        add(x, y, z, Material.MOSSY_COBBLESTONE, null, 0, y);
                    }
                    if (Math.abs(dx) == 3 && Math.abs(dz) == 3) {
                        add(x, cy, z, Material.MOSSY_STONE_BRICKS, null, 4, 0);
                        add(x, cy + 1, z, Material.MOSSY_STONE_BRICKS, null, 4, 1);
                        add(x, cy + 2, z, Material.EMERALD_BLOCK, null, 4, 2);
                        add(x, cy + 3, z, Material.LIME_STAINED_GLASS, null, 4, 3);
                    }
                }
            }
        }
        // 4 скрині навколо басейну, лицем до центру
        add(cx, cy, cz - 2, Material.CHEST, BlockFace.SOUTH, 5, 0);
        add(cx + 2, cy, cz, Material.CHEST, BlockFace.WEST, 5, 1);
        add(cx, cy, cz + 2, Material.CHEST, BlockFace.NORTH, 5, 2);
        add(cx - 2, cy, cz, Material.CHEST, BlockFace.EAST, 5, 3);

        // розчистка: трава, квіти, невеликі горби над майданчиком
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int y = cy; y <= cy + 5; y++) {
                    int x = cx + dx, z = cz + dz;
                    if (struct.contains(x + "," + y + "," + z)) continue;
                    Material m = world.getBlockAt(x, y, z).getType();
                    if (m != Material.AIR && !m.name().endsWith("_AIR")) {
                        parts.add(new Part(x, y, z, Material.AIR, null, 0, y));
                    }
                }
            }
        }
        Collections.sort(parts, new Comparator<Part>() {
            @Override
            public int compare(Part a, Part b) {
                if (a.group != b.group) return Integer.compare(a.group, b.group);
                return Double.compare(a.sub, b.sub);
            }
        });
        for (Part p : parts) groupSize[p.group]++;
    }

    /** Скільки частин має бути поставлено при прогресі 0..1 (кожна група має свою частку часу). */
    private int target(double prog) {
        if (prog >= 1.0) return parts.size();
        double acc = 0;
        int idx = 0;
        for (int g = 0; g < W.length; g++) {
            if (prog >= acc + W[g]) {
                idx += groupSize[g];
                acc += W[g];
                continue;
            }
            idx += (int) Math.ceil(groupSize[g] * (prog - acc) / W[g]);
            break;
        }
        return Math.min(idx, parts.size());
    }

    boolean contains(Block b) {
        return !finished && b.getWorld().equals(world)
                && Math.abs(b.getX() - cx) <= 3 && Math.abs(b.getZ() - cz) <= 3
                && b.getY() >= cy - 3 && b.getY() <= cy + 6;
    }

    boolean isChest(Block b) {
        if (finished || !b.getWorld().equals(world)) return false;
        for (Part p : chests) {
            if (p.x == b.getX() && p.y == b.getY() && p.z == b.getZ()) return true;
        }
        return false;
    }

    // ================================================================ блоки

    private void apply(Part p) {
        Block b = world.getBlockAt(p.x, p.y, p.z);
        if (p.old == null) p.old = b.getBlockData();
        b.setType(p.mat, false);
        if (p.face != null) {
            BlockData d = b.getBlockData();
            if (d instanceof Directional) {
                ((Directional) d).setFacing(p.face);
                b.setBlockData(d, false);
            }
        }
        p.placed = true;
    }

    private void restore(Part p) {
        if (!p.placed) return;
        Block b = world.getBlockAt(p.x, p.y, p.z);
        if (p.mat == Material.CHEST) {
            Inventory inv = chestInv(p);
            if (inv != null) inv.clear();
        }
        if (p.old != null) b.setBlockData(p.old, false);
        p.placed = false;
    }

    private Inventory chestInv(Part p) {
        BlockState s = world.getBlockAt(p.x, p.y, p.z).getState();
        return s instanceof Chest ? ((Chest) s).getBlockInventory() : null;
    }

    // ================================================================ ефекти

    private void dust(Location l, int count, double spread) {
        if (DUST == null) return;
        try {
            world.spawnParticle(DUST, l, count, spread, spread / 2, spread, 0,
                    new Particle.DustOptions(Color.fromRGB(57, 255, 20), 1.6f));
        } catch (Throwable ignored) {
        }
    }

    private void happy(Location l, int count) {
        if (HAPPY == null) return;
        try {
            world.spawnParticle(HAPPY, l, count, 0.4, 0.4, 0.4, 0);
        } catch (Throwable ignored) {
        }
    }

    private void sound(Location l, String key, float vol, float pitch) {
        try {
            world.playSound(l, key, vol, pitch);
        } catch (Throwable ignored) {
        }
    }

    private Location center() {
        return new Location(world, cx + 0.5, cy + 0.5, cz + 0.5);
    }

    private void ring(double r, double y, int n) {
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n;
            dust(new Location(world, cx + 0.5 + Math.cos(a) * r, y, cz + 0.5 + Math.sin(a) * r), 1, 0.02);
        }
    }

    private void burst() {
        for (int k = 1; k <= 4; k++) ring(k, cy + 0.3, 12 + k * 6);
        happy(center(), 25);
    }

    private void title(String t, String s) {
        Location c = center();
        for (Player p : world.getPlayers()) {
            if (p.getLocation().distanceSquared(c) <= 1600) p.sendTitle(t, s, 5, 40, 10);
        }
    }

    /** Зелена "кислота" над басейном; intensity 0..1 - наскільки густо. */
    private void ambient(double intensity) {
        int n = 5 + (int) (intensity * 12);
        for (int i = 0; i < n; i++) {
            dust(new Location(world, cx - 1 + rnd.nextDouble() * 3, cy - 0.1 + rnd.nextDouble() * intensity * 1.5,
                    cz - 1 + rnd.nextDouble() * 3), 1, 0.05);
        }
        for (int s = -1; s <= 1; s += 2) {
            for (int t = -1; t <= 1; t += 2) {
                happy(new Location(world, cx + 0.5 + 3 * s, cy + 3.3, cz + 0.5 + 3 * t), 1);
            }
        }
    }

    private void poison() {
        if (tick % 10 != 0) return;
        int sec = pl.getConfig().getInt("altar.poison.seconds", 8);
        int lvl = Math.max(0, pl.getConfig().getInt("altar.poison.level", 2) - 1);
        for (Player p : world.getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            Location l = p.getLocation();
            int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
            if (x < cx - 1 || x > cx + 1 || z < cz - 1 || z > cz + 1 || y < cy - 3 || y > cy - 1) continue;
            if (l.getBlock().getType() != Material.WATER) continue;
            p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, sec * 20, lvl));
        }
    }

    private void bar(String title, double progress) {
        bar.setTitle(title);
        bar.setProgress(Math.max(0.0, Math.min(1.0, progress)));
        double r = pl.getConfig().getDouble("altar.bossbar-radius", 80);
        Location c = new Location(world, cx, cy, cz);
        List<Player> cur = bar.getPlayers();
        for (Player p : world.getPlayers()) {
            boolean in = p.getLocation().distanceSquared(c) <= r * r;
            boolean has = cur.contains(p);
            if (in && !has) bar.addPlayer(p);
            else if (!in && has) bar.removePlayer(p);
        }
    }

    private String xyz(String key) {
        return pl.msg(key, "x", String.valueOf(cx), "y", String.valueOf(cy), "z", String.valueOf(cz),
                "player", owner, "seconds", String.valueOf(secondsLeft));
    }

    private void setState(State s) {
        state = s;
        stateTick = 0;
    }

    // ================================================================ головний цикл

    void tick() {
        tick++;
        stateTick++;
        poison();
        switch (state) {
            case BUILDING:
                tickBuild();
                break;
            case READY:
                tickReady();
                break;
            case ACTIVATING:
                tickActivating();
                break;
            case REVEAL:
                tickReveal();
                break;
            case OPEN:
                tickOpen();
                break;
            case REMOVING:
                tickRemoving();
                break;
            default:
                break;
        }
    }

    private void fx(Part p, double prog) {
        Location top = new Location(world, p.x + 0.5, p.y + 1.0, p.z + 0.5);
        switch (p.group) {
            case 2:
                if (rnd.nextInt(3) == 0) {
                    dust(top, 2, 0.2);
                    sound(top, "entity.player.splash", 0.3f, 1.4f);
                }
                break;
            case 3:
                dust(top, 3, 0.25);
                happy(top, 1);
                if (rnd.nextInt(3) == 0) sound(top, "block.stone.place", 0.8f, 0.6f + (float) prog * 0.8f);
                break;
            case 4:
                dust(top, 4, 0.2);
                if (p.mat == Material.EMERALD_BLOCK) {
                    happy(top, 6);
                    sound(top, "block.note_block.chime", 1f, 1.2f);
                } else if (rnd.nextInt(2) == 0) {
                    sound(top, "block.stone.place", 0.8f, 0.9f);
                }
                break;
            case 5:
                dust(top, 20, 0.5);
                happy(top, 8);
                sound(top, "block.ender_chest.open", 1f, 1.3f);
                sound(top, "entity.experience_orb.pickup", 1f, 1.5f);
                break;
            default:
                break;
        }
    }

    private void tickBuild() {
        int dur = Math.max(20, pl.getConfig().getInt("altar.build-seconds", 9) * 20);
        double prog = Math.min(1.0, stateTick / (double) dur);
        if (stateTick == 1) {
            title(pl.msg("title-build"), pl.msg("subtitle-build"));
            sound(center(), "entity.lightning_bolt.thunder", 0.9f, 0.6f);
        }
        int target = target(prog);
        while (placed < target) {
            Part p = parts.get(placed++);
            apply(p);
            fx(p, prog);
        }
        // хвиля, що розходиться по землі, і зелені спіралі над кутами
        if (stateTick % 3 == 0 && prog < 0.95) ring(((stateTick % 30) / 30.0) * 4.0, cy + 0.15, 28);
        if (stateTick % 2 == 0) {
            for (int s = -1; s <= 1; s += 2) {
                for (int t = -1; t <= 1; t += 2) {
                    double a = stateTick * 0.4;
                    dust(new Location(world, cx + 0.5 + 3 * s + Math.cos(a) * 0.6, cy + (stateTick * 0.12) % 4.0,
                            cz + 0.5 + 3 * t + Math.sin(a) * 0.6), 1, 0.02);
                }
            }
        }
        if (stateTick % 5 == 1) bar(InterAltar.color(pl.getConfig().getString("messages.bar-build", "")), prog);
        if (placed >= parts.size() && prog >= 1.0) {
            setState(State.READY);
            bar(InterAltar.color(pl.getConfig().getString("messages.bar-ready", "")), 1.0);
            Bukkit.broadcastMessage(xyz("ready"));
            title(pl.msg("title-ready"), pl.msg("subtitle-ready"));
            burst();
            sound(center(), "block.beacon.activate", 1f, 1.2f);
        }
    }

    private void tickReady() {
        if (stateTick % 5 == 0) ambient(0.2);
        if (stateTick % 20 == 0) bar(InterAltar.color(pl.getConfig().getString("messages.bar-ready", "")), 1.0);
        int timeout = pl.getConfig().getInt("altar.ready-timeout-seconds", 900) * 20;
        if (stateTick > timeout) {
            Bukkit.broadcastMessage(xyz("expired"));
            beginRemove(false);
        }
    }

    void click(Player p) {
        switch (state) {
            case READY:
                secondsLeft = Math.max(1, pl.getConfig().getInt("altar.activation-seconds", 20));
                setState(State.ACTIVATING);
                Bukkit.broadcastMessage(pl.msg("activated", "player", p.getName(), "seconds", String.valueOf(secondsLeft),
                        "x", String.valueOf(cx), "y", String.valueOf(cy), "z", String.valueOf(cz)));
                sound(center(), "entity.lightning_bolt.thunder", 1f, 1.4f);
                burst();
                break;
            case ACTIVATING:
                p.sendMessage(pl.msg("wait-activation", "seconds", String.valueOf(secondsLeft)));
                break;
            case REVEAL:
                break;
            default:
                p.sendMessage(pl.msg("wait-build"));
                break;
        }
    }

    private void tickActivating() {
        int total = Math.max(1, pl.getConfig().getInt("altar.activation-seconds", 20));
        double done = 1.0 - Math.max(0, secondsLeft) / (double) total;
        if (stateTick % 3 == 0) ambient(0.4 + done * 0.6);
        if (stateTick % 10 == 0) ring(1.5 + ((stateTick % 40) / 40.0) * 2.5, cy + 0.2, 30);
        if (stateTick % 20 == 0) {
            secondsLeft--;
            bar(pl.msg("bar-activating", "seconds", String.valueOf(Math.max(0, secondsLeft))), secondsLeft / (double) total);
            if (secondsLeft > 0 && secondsLeft <= 5) {
                sound(center(), "block.note_block.pling", 1f, 0.8f + (5 - secondsLeft) * 0.2f);
                Location c = center();
                for (Player p : world.getPlayers()) {
                    if (p.getLocation().distanceSquared(c) <= 900) {
                        p.sendTitle(InterAltar.color("&#39ff14&l" + secondsLeft), "", 0, 22, 3);
                    }
                }
            }
            if (secondsLeft <= 0) {
                setState(State.REVEAL);
                revealIdx = 0;
                revealPhase = 0;
                Bukkit.broadcastMessage(xyz("reveal"));
                sound(center(), "entity.lightning_bolt.thunder", 1f, 1.0f);
                burst();
            }
        }
    }

    /**
     * Скрині відкриваються: спершу в усіх слотах поступово з'являється темно-зелений барвник,
     * потім він по одному слоту замінюється справжнім лутом.
     */
    private void tickReveal() {
        if (stateTick % 4 == 0) ambient(0.8);
        if (revealPhase == 0) {
            int step = Math.max(1, pl.getConfig().getInt("altar.dye-step-ticks", 2));
            if (stateTick % step != 0) return;
            if (revealIdx < 27) {
                int slot = orderDye.get(revealIdx++);
                for (Part cp : chests) {
                    Inventory inv = chestInv(cp);
                    if (inv != null) inv.setItem(slot, new ItemStack(Material.GREEN_DYE));
                }
                sound(center(), "block.slime_block.place", 0.8f, 0.5f + revealIdx / 27f);
            } else {
                revealPhase = 1;
                revealIdx = 0;
            }
        } else {
            int step = Math.max(1, pl.getConfig().getInt("altar.loot-step-ticks", 3));
            if (stateTick % step != 0) return;
            if (revealIdx < 27) {
                int slot = orderLoot.get(revealIdx++);
                for (int i = 0; i < chests.size(); i++) {
                    Inventory inv = chestInv(chests.get(i));
                    if (inv == null) continue;
                    ItemStack it = loot[i][slot];
                    inv.setItem(slot, it == null ? null : it.clone());
                }
                sound(center(), "entity.experience_orb.pickup", 1f, 0.5f + revealIdx / 27f * 1.5f);
            } else {
                secondsLeft = Math.max(1, pl.getConfig().getInt("altar.open-seconds", 180));
                setState(State.OPEN);
                Bukkit.broadcastMessage(xyz("open"));
                sound(center(), "entity.player.levelup", 1f, 1f);
                burst();
            }
        }
    }

    private void tickOpen() {
        if (stateTick % 5 == 0) ambient(0.5);
        if (stateTick % 20 == 0) {
            secondsLeft--;
            int total = Math.max(1, pl.getConfig().getInt("altar.open-seconds", 180));
            bar(pl.msg("bar-open", "seconds", String.valueOf(Math.max(0, secondsLeft))), secondsLeft / (double) total);
            if (secondsLeft <= 0) beginRemove(true);
        }
    }

    private void beginRemove(boolean announce) {
        removeBase = placed;
        removeIdx = placed;
        setState(State.REMOVING);
        if (announce) Bukkit.broadcastMessage(xyz("removed"));
    }

    private void tickRemoving() {
        int dur = Math.max(20, pl.getConfig().getInt("altar.remove-seconds", 6) * 20);
        int target = (int) Math.ceil(removeBase * (1.0 - (double) stateTick / dur));
        boolean played = false;
        while (removeIdx > Math.max(0, target)) {
            Part p = parts.get(--removeIdx);
            restore(p);
            if (!played && removeIdx % 2 == 0) {
                played = true;
                Location l = new Location(world, p.x + 0.5, p.y + 0.5, p.z + 0.5);
                dust(l, 2, 0.3);
                sound(l, "block.stone.break", 0.6f, 0.7f + rnd.nextFloat() * 0.6f);
            }
        }
        if (removeIdx <= 0) {
            bar.removeAll();
            finished = true;
        }
    }

    /** Миттєве прибирання (вимкнення сервера, /acidaltar clear, помилка). */
    void forceRemove() {
        for (int i = parts.size() - 1; i >= 0; i--) restore(parts.get(i));
        bar.removeAll();
        finished = true;
    }
}
