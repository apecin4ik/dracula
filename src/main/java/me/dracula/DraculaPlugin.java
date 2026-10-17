package me.dracula;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.session.ClipboardHolder;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.time.*;
import java.util.*;

public class DraculaPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {

    public enum State { IDLE, LOCKED, OPEN }

    private State state = State.IDLE;
    private World world;
    private Location base;
    private Location chestLoc;
    private Inventory lootInv;
    private BossBar bar;
    private int left;
    private long unlockAt, endAt;           // epoch millis: персистентные дедлайны
    private LocalDate lastSpawnDay;
    private final Set<Integer> alerted = new HashSet<>();
    private final Random rnd = new Random();
    private int saveCounter;

    private Clipboard clipboard;
    private BlockVector3 chestRel;          // положение алтаря-маркера внутри схематики (от min-угла)
    private BlockVector3 towerMin, towerMax;
    private Clipboard backup;               // что было на месте башни до вставки
    private FileConfiguration loot;
    private GuardManager guards;

    private File stateFile() { return new File(getDataFolder(), "state.yml"); }
    private File backupFile() { return new File(getDataFolder(), "terrain-backup.schem"); }

    // ------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("loot.yml", false);
        File schem = new File(getDataFolder(), getConfig().getString("schematic", "basnea_drakula.schem"));
        if (!schem.exists()) saveResource("basnea_drakula.schem", false);
        loadAll();

        bar = Bukkit.createBossBar("", BarColor.RED, BarStyle.SEGMENTED_10);
        bar.setVisible(true);
        guards = new GuardManager(this);

        PluginCommand cmd = getCommand("dracula");
        cmd.setExecutor(this);
        cmd.setTabCompleter(this);
        Bukkit.getPluginManager().registerEvents(new EventListener(this), this);
        Bukkit.getPluginManager().registerEvents(guards, this);

        loadState();
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
    }

    @Override
    public void onDisable() {
        // башня остаётся в мире, состояние сохраняем и продолжим после рестарта
        saveState();
        if (guards != null) guards.clear();
        if (bar != null) bar.removeAll();
    }

    private void loadAll() {
        reloadConfig();
        world = Bukkit.getWorld(getConfig().getString("world", "world"));
        loot = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "loot.yml"));
        File sf = new File(getDataFolder(), getConfig().getString("schematic", "basnea_drakula.schem"));
        clipboard = null;
        chestRel = null;
        if (!sf.exists()) {
            getLogger().warning("Схематика не найдена: " + sf.getPath());
            return;
        }
        ClipboardFormat fmt = ClipboardFormats.findByFile(sf);
        try (ClipboardReader r = fmt.getReader(new FileInputStream(sf))) {
            clipboard = r.read();
        } catch (Exception e) {
            getLogger().severe("Ошибка чтения схематики: " + e.getMessage());
            return;
        }
        // ищем чёрный шулкер внутри схематики — это место главного приза
        BlockVector3 min = clipboard.getRegion().getMinimumPoint();
        for (BlockVector3 p : clipboard.getRegion()) {
            if (clipboard.getBlock(p).getBlockType().getId().equals("minecraft:black_shulker_box")) {
                chestRel = p.subtract(min);
                break;
            }
        }
        if (chestRel == null) {
            ConfigurationSection co = getConfig().getConfigurationSection("chest-offset");
            chestRel = BlockVector3.at(co.getInt("x"), co.getInt("y"), co.getInt("z"));
            getLogger().warning("Маркер шулкера в схематике не найден, использую chest-offset из конфига.");
        }
    }

    // ------------------------------------------------------------ persistence

    public void saveState() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("state", state.name());
        y.set("lastSpawnDay", lastSpawnDay == null ? null : lastSpawnDay.toString());
        if (state != State.IDLE && towerMin != null) {
            y.set("world", world.getName());
            y.set("min", Arrays.asList(towerMin.getBlockX(), towerMin.getBlockY(), towerMin.getBlockZ()));
            y.set("max", Arrays.asList(towerMax.getBlockX(), towerMax.getBlockY(), towerMax.getBlockZ()));
            y.set("base", Arrays.asList(base.getBlockX(), base.getBlockY(), base.getBlockZ()));
            y.set("chest", Arrays.asList(chestLoc.getBlockX(), chestLoc.getBlockY(), chestLoc.getBlockZ()));
            y.set("unlockAt", unlockAt);
            y.set("endAt", endAt);
            if (lootInv != null) y.set("loot", Arrays.asList(lootInv.getContents()));
        }
        try { y.save(stateFile()); } catch (IOException e) { getLogger().warning("Не удалось сохранить state.yml: " + e.getMessage()); }
    }

    private void loadState() {
        File f = stateFile();
        if (!f.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        String d = y.getString("lastSpawnDay");
        if (d != null) lastSpawnDay = LocalDate.parse(d);
        State st = State.valueOf(y.getString("state", "IDLE"));
        if (st == State.IDLE) return;

        world = Bukkit.getWorld(y.getString("world", ""));
        if (world == null) { getLogger().warning("Мир ивента не найден, состояние сброшено."); return; }
        List<Integer> mn = y.getIntegerList("min"), mx = y.getIntegerList("max"), bs = y.getIntegerList("base"), ch = y.getIntegerList("chest");
        towerMin = BlockVector3.at(mn.get(0), mn.get(1), mn.get(2));
        towerMax = BlockVector3.at(mx.get(0), mx.get(1), mx.get(2));
        base = new Location(world, bs.get(0), bs.get(1), bs.get(2));
        chestLoc = new Location(world, ch.get(0), ch.get(1), ch.get(2));
        unlockAt = y.getLong("unlockAt");
        endAt = y.getLong("endAt");
        long now = System.currentTimeMillis();

        if (now >= endAt) {                    // пока сервер лежал, ивент закончился
            state = st;
            removeTower();
            getLogger().info("Ивент закончился во время простоя, башня убрана.");
            return;
        }
        if (now >= unlockAt) {
            state = State.OPEN;
            chestLoc.getBlock().setType(Material.RED_SHULKER_BOX);
            lootInv = Bukkit.createInventory(null, 54, color(getConfig().getString("chest-title")));
            List<?> saved = y.getList("loot");
            if (saved != null) {
                for (int i = 0; i < saved.size() && i < 54; i++) {
                    if (saved.get(i) instanceof ItemStack it) lootInv.setItem(i, it);
                }
            }
        } else {
            state = State.LOCKED;
            chestLoc.getBlock().setType(Material.BLACK_SHULKER_BOX);
        }
        guards.spawn(world, towerMin, towerMax);
        getLogger().info("Состояние ивента восстановлено: " + state);
    }

    // ------------------------------------------------------------ main tick

    private void tick() {
        long now = System.currentTimeMillis();
        switch (state) {
            case IDLE -> idleTick();
            case LOCKED -> {
                left = (int) Math.max(0, (unlockAt - now + 999) / 1000);
                if (now >= unlockAt) unlock();
                else showBar("locked", 1.0 - left / (double) getConfig().getInt("unlock-seconds"));
            }
            case OPEN -> {
                left = (int) Math.max(0, (endAt - now + 999) / 1000);
                if (now >= endAt) endEvent(true);
                else showBar("open", left / (double) getConfig().getInt("end-after-open-seconds"));
            }
        }
        if (state != State.IDLE && ++saveCounter >= 15) { saveCounter = 0; saveState(); }
    }

    private ZonedDateTime now() {
        return ZonedDateTime.now(ZoneId.of(getConfig().getString("timezone", "Europe/Moscow")));
    }

    private LocalTime spawnTime() {
        return LocalTime.parse(getConfig().getString("spawn-time", "22:00"));
    }

    private long secondsToNext() {
        ZonedDateTime n = now();
        ZonedDateTime next = n.with(spawnTime());
        if (!next.isAfter(n)) next = next.plusDays(1);
        return Duration.between(n, next).getSeconds();
    }

    private void idleTick() {
        ZonedDateTime n = now();
        LocalDate today = n.toLocalDate();
        LocalTime st = spawnTime();
        boolean inWindow = !n.toLocalTime().isBefore(st) && n.toLocalTime().isBefore(st.plusMinutes(1));
        if (inWindow && !today.equals(lastSpawnDay)) {
            lastSpawnDay = today;
            alerted.clear();
            startEvent();
            return;
        }
        long s = secondsToNext();
        for (int a : getConfig().getIntegerList("alerts")) {
            if (s <= a && s > a - 5 && alerted.add(a)) {
                if (a <= 60) broadcast(getConfig().getString("messages.alert-1min"));
                else broadcast(getConfig().getString("messages.alert-long").replace("{time}", fmt(a)));
            }
        }
        showBar("idle", Math.max(0, Math.min(1, 1.0 - s / 7200.0)));
    }

    // ------------------------------------------------------------ event

    public boolean startEvent() {
        if (state != State.IDLE) return false;
        if (clipboard == null || world == null) return false;
        Location spot = findSpot();
        if (spot == null) return false;

        base = spot;
        pasteTower();
        chestLoc = new Location(world, towerMin.getBlockX() + chestRel.getBlockX(),
                towerMin.getBlockY() + chestRel.getBlockY(), towerMin.getBlockZ() + chestRel.getBlockZ());
        chestLoc.getBlock().setType(Material.BLACK_SHULKER_BOX);

        long now = System.currentTimeMillis();
        unlockAt = now + getConfig().getInt("unlock-seconds") * 1000L;
        endAt = unlockAt + getConfig().getInt("end-after-open-seconds") * 1000L;
        left = getConfig().getInt("unlock-seconds");
        state = State.LOCKED;
        guards.spawn(world, towerMin, towerMax);
        saveState();

        world.strikeLightningEffect(chestLoc);
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.6f, 0.6f);
        broadcastList("messages.spawned", left);
        return true;
    }

    private void unlock() {
        chestLoc.getBlock().setType(Material.RED_SHULKER_BOX);
        lootInv = Bukkit.createInventory(null, 54, color(getConfig().getString("chest-title")));
        fillLoot();
        state = State.OPEN;
        left = (int) Math.max(0, (endAt - System.currentTimeMillis()) / 1000);
        saveState();
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.BLOCK_END_PORTAL_SPAWN, 0.5f, 0.7f);
        broadcastList("messages.unlocked", left);
    }

    public void endEvent(boolean announce) {
        if (state == State.IDLE) return;
        removeTower();
        if (announce) broadcast(getConfig().getString("messages.ended"));
    }

    /** Убирает башню и возвращает местность такой, какой она была до вставки. */
    private void removeTower() {
        if (guards != null) guards.clear();
        if (lootInv != null) new ArrayList<>(lootInv.getViewers()).forEach(h -> h.closeInventory());
        lootInv = null;

        Clipboard bk = backup;
        if (bk == null && backupFile().exists()) {
            try (ClipboardReader r = ClipboardFormats.findByFile(backupFile()).getReader(new FileInputStream(backupFile()))) {
                bk = r.read();
            } catch (Exception e) {
                getLogger().warning("Не удалось прочитать резервную копию местности: " + e.getMessage());
            }
        }
        try (EditSession es = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world))) {
            if (bk != null) {
                Operation op = new ClipboardHolder(bk).createPaste(es).to(towerMin).ignoreAirBlocks(false).build();
                Operations.complete(op);
            } else {
                CuboidRegion region = new CuboidRegion(BukkitAdapter.adapt(world), towerMin, towerMax);
                es.setBlocks(region, com.sk89q.worldedit.world.block.BlockTypes.AIR.getDefaultState());
            }
        } catch (Exception e) {
            getLogger().severe("Не удалось убрать башню: " + e.getMessage());
        }
        backup = null;
        backupFile().delete();

        state = State.IDLE;
        base = null;
        chestLoc = null;
        saveState();
    }

    private void pasteTower() {
        BlockVector3 to = BlockVector3.at(base.getBlockX(), base.getBlockY() + getConfig().getInt("paste-y-offset"), base.getBlockZ());
        BlockVector3 shift = to.subtract(clipboard.getOrigin());
        towerMin = clipboard.getRegion().getMinimumPoint().add(shift);
        towerMax = clipboard.getRegion().getMaximumPoint().add(shift);

        try (EditSession es = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world))) {
            // 1) запоминаем местность
            CuboidRegion reg = new CuboidRegion(BukkitAdapter.adapt(world), towerMin, towerMax);
            backup = new BlockArrayClipboard(reg);
            ForwardExtentCopy copy = new ForwardExtentCopy(es, reg, backup, reg.getMinimumPoint());
            Operations.complete(copy);
            try (ClipboardWriter w = BuiltInClipboardFormat.SPONGE_SCHEMATIC.getWriter(new FileOutputStream(backupFile()))) {
                w.write(backup);
            } catch (Exception e) {
                getLogger().warning("Резервная копия местности не записана на диск: " + e.getMessage());
            }
            // 2) вставляем башню
            Operation op = new ClipboardHolder(clipboard).createPaste(es).to(to).ignoreAirBlocks(false).build();
            Operations.complete(op);
        } catch (Exception e) {
            getLogger().severe("Ошибка вставки: " + e.getMessage());
        }
    }

    private Location findSpot() {
        int min = getConfig().getInt("radius-min");
        int max = getConfig().getInt("radius-max");
        for (int i = 0; i < 40; i++) {
            int x = min + rnd.nextInt(max - min + 1);
            int z = min + rnd.nextInt(max - min + 1);
            if (!solidGround(x, z)) continue;
            // не ставим на берегу/в воде: проверяем углы площадки
            boolean ok = true;
            for (int[] d : new int[][]{{-20, -20}, {20, -20}, {-20, 20}, {20, 20}}) {
                if (!solidGround(x + d[0], z + d[1])) { ok = false; break; }
            }
            if (!ok) continue;
            return new Location(world, x, world.getHighestBlockYAt(x, z) + 1, z);
        }
        return null;
    }

    private boolean solidGround(int x, int z) {
        int y = world.getHighestBlockYAt(x, z);
        Block b = world.getBlockAt(x, y, z);
        Material m = b.getType();
        return !(m.isAir() || b.isLiquid() || Tag.LEAVES.isTagged(m) || Tag.LOGS.isTagged(m) || m == Material.ICE || m == Material.LAVA);
    }

    private void fillLoot() {
        ConfigurationSection inv = loot.getConfigurationSection("inv");
        if (inv == null) return;
        for (String id : inv.getKeys(false)) {
            ConfigurationSection slots = inv.getConfigurationSection(id);
            for (String slotKey : slots.getKeys(false)) {
                ConfigurationSection chances = slots.getConfigurationSection(slotKey);
                for (String chanceKey : chances.getKeys(false)) {
                    int slot = Integer.parseInt(slotKey);
                    int chance = Integer.parseInt(chanceKey);
                    ItemStack it = chances.getItemStack(chanceKey);
                    if (it != null && slot < 54 && rnd.nextInt(100) < chance) lootInv.setItem(slot, it);
                }
            }
        }
    }

    // ------------------------------------------------------------ bossbar

    private void showBar(String key, double progress) {
        String title = getConfig().getString("bossbar." + key, "")
                .replace("{time}", fmt(key.equals("idle") ? secondsToNext() : left));
        if (chestLoc != null) {
            title = title.replace("{x}", String.valueOf(chestLoc.getBlockX()))
                    .replace("{y}", String.valueOf(chestLoc.getBlockY()))
                    .replace("{z}", String.valueOf(chestLoc.getBlockZ()));
        }
        bar.setTitle(color(title));
        bar.setProgress(Math.max(0, Math.min(1, progress)));
        bar.setColor(key.equals("open") ? BarColor.PURPLE : BarColor.RED);
        for (Player p : Bukkit.getOnlinePlayers()) bar.addPlayer(p);
    }

    // ------------------------------------------------------------ helpers (also used by listener)

    public Random random() { return rnd; }
    public State getState() { return state; }
    public Location getBase() { return base; }
    public Location getChestLoc() { return chestLoc; }
    public Inventory getLootInv() { return lootInv; }
    public int getLeft() { return left; }
    public int protectRadius() { return getConfig().getInt("protect-radius"); }

    public static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    public String msg(String path) {
        return color(getConfig().getString("messages.prefix") + getConfig().getString(path, path));
    }

    public static String fmt(long s) {
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format("%02d:%02d:%02d", h, m, sec) : String.format("%02d:%02d", m, sec);
    }

    private String fill(String s, int time) {
        String r = s.replace("{time}", fmt(time));
        if (chestLoc != null) r = r.replace("{x}", "" + chestLoc.getBlockX()).replace("{y}", "" + chestLoc.getBlockY()).replace("{z}", "" + chestLoc.getBlockZ());
        return r;
    }

    private void broadcast(String s) {
        Bukkit.broadcastMessage(color(getConfig().getString("messages.prefix") + s));
    }

    private void broadcastList(String path, int time) {
        for (String l : getConfig().getStringList(path)) Bukkit.broadcastMessage(color(fill(l, time)));
    }

    // ------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        boolean admin = s.hasPermission("dracula.admin");
        String sub = a.length > 0 ? a[0].toLowerCase() : "info";

        if (admin) {
            switch (sub) {
                case "start" -> {
                    if (state != State.IDLE) s.sendMessage(msg("messages.already-running"));
                    else if (!startEvent()) s.sendMessage(msg("messages.no-spawn"));
                    return true;
                }
                case "stop" -> {
                    if (state == State.IDLE) s.sendMessage(msg("messages.not-running"));
                    else endEvent(true);
                    return true;
                }
                case "reload" -> {
                    loadAll();
                    s.sendMessage(msg("messages.reloaded"));
                    return true;
                }
                case "tp" -> {
                    if (!(s instanceof Player p)) return true;
                    if (state == State.IDLE) p.sendMessage(msg("messages.not-running"));
                    else p.teleport(chestLoc.clone().add(0.5, 1, 2.5));
                    return true;
                }
                default -> { }
            }
        }

        String spawn = getConfig().getString("spawn-time") + " МСК";
        for (String l : getConfig().getStringList("info-lines")) {
            s.sendMessage(color(l.replace("{spawn}", spawn).replace("{lock}", fmt(getConfig().getInt("unlock-seconds")))));
        }
        String statusKey = switch (state) {
            case IDLE -> "info.status-idle";
            case LOCKED -> "info.status-locked";
            case OPEN -> "info.status-open";
        };
        long t = state == State.IDLE ? secondsToNext() : left;
        s.sendMessage(color(fill(getConfig().getString(statusKey), (int) t)));
        if (admin) {
            s.sendMessage(color(getConfig().getString("info.admin-header")));
            for (String l : getConfig().getStringList("info.admin")) s.sendMessage(color(l));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        if (!s.hasPermission("dracula.admin") || a.length != 1) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String o : List.of("start", "stop", "reload", "tp")) if (o.startsWith(a[0].toLowerCase())) out.add(o);
        return out;
    }
}
