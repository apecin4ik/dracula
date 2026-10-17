package me.dracula;

import com.sk89q.worldedit.math.BlockVector3;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Стражи Дракулы: 2 наземных на каждом из 4 этажей + 4 летающих над башней.
 * Тотем в левой руке: когда он срабатывает, страж звереет (меньше HP, больше урона).
 */
public class GuardManager implements Listener {

    /** Атрибут урона незеритового меча (+7), нужен чтобы итоговый урон был ровно как в конфиге. */
    private static final double SWORD_BONUS = 7.0;

    private final DraculaPlugin plugin;
    private final NamespacedKey key;
    private final Map<UUID, Location> homes = new HashMap<>();
    private final List<long[]> tickets = new ArrayList<>();
    private BukkitTask leashTask;

    private World world;
    private int minX, minY, minZ, maxX, maxY, maxZ;

    public GuardManager(DraculaPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "dracula_guard");
    }

    // ------------------------------------------------------------ spawn

    public void spawn(World w, BlockVector3 min, BlockVector3 max) {
        clear();
        this.world = w;
        minX = min.getBlockX(); minY = min.getBlockY(); minZ = min.getBlockZ();
        maxX = max.getBlockX(); maxY = max.getBlockY(); maxZ = max.getBlockZ();

        // держим чанки башни загруженными, чтобы стражи не "засыпали"
        for (int cx = (minX >> 4) - 1; cx <= (maxX >> 4) + 1; cx++) {
            for (int cz = (minZ >> 4) - 1; cz <= (maxZ >> 4) + 1; cz++) {
                w.getChunkAt(cx, cz).addPluginChunkTicket(plugin);
                tickets.add(new long[]{cx, cz});
            }
        }

        ConfigurationSection g = plugin.getConfig().getConfigurationSection("guards");
        int perFloor = g.getInt("per-floor", 2);
        int search = g.getInt("floor-search-height", 8);
        // центр башни считаем от min-угла схематики (в декорированной схеме башня не по центру)
        int centerX = minX + g.getInt("tower-center.x", (maxX - minX) / 2);
        int centerZ = minZ + g.getInt("tower-center.z", (maxZ - minZ) / 2);
        int spreadX = g.getInt("spread.x", 3);
        int spreadZ = g.getInt("spread.z", 3);

        // наземные стражи
        for (int floorLayer : g.getIntegerList("floors")) {
            for (int i = 0; i < perFloor; i++) {
                Location spot = findFloorSpot(centerX, centerZ, spreadX, spreadZ, minY + floorLayer, search);
                if (spot == null) {
                    plugin.getLogger().warning("Не нашёл место для стража на слое " + floorLayer + " — поправь guards.floors в config.yml");
                    continue;
                }
                spawnGround(spot);
            }
        }

        // летающие стражи над башней
        int count = g.getInt("flying.count", 4);
        int above = g.getInt("flying.height-above", 6);
        for (int i = 0; i < count; i++) {
            double ang = 2 * Math.PI * i / count;
            Location l = new Location(w, centerX + 0.5 + Math.cos(ang) * 4, maxY + above, centerZ + 0.5 + Math.sin(ang) * 4);
            spawnFlying(l);
        }

        leashTask = Bukkit.getScheduler().runTaskTimer(plugin, this::leashTick, 10L, 10L);
    }

    private Location findFloorSpot(int cx, int cz, int sx, int sz, int baseY, int search) {
        Random r = plugin.random();
        for (int attempt = 0; attempt < 80; attempt++) {
            int x = cx + r.nextInt(sx * 2 + 1) - sx;
            int z = cz + r.nextInt(sz * 2 + 1) - sz;
            for (int y = baseY; y <= baseY + search; y++) {
                Block below = world.getBlockAt(x, y - 1, z);
                Block feet = world.getBlockAt(x, y, z);
                Block head = world.getBlockAt(x, y + 1, z);
                if (below.getType().isSolid() && free(feet) && free(head)) {
                    return new Location(world, x + 0.5, y, z + 0.5);
                }
            }
        }
        return null;
    }

    private boolean free(Block b) {
        return !b.getType().isSolid() && !b.isLiquid();
    }

    private void spawnGround(Location loc) {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("guards.ground");
        PiglinBrute m = world.spawn(loc, PiglinBrute.class, e -> {
            e.setImmuneToZombification(true);
            e.setAdult();
            equip(e, true);
            setup(e, s.getDouble("health", 80), s.getDouble("damage", 8), "ground");
        });
        homes.put(m.getUniqueId(), loc.clone());
    }

    private void spawnFlying(Location loc) {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("guards.flying");
        Location bound = loc.clone();
        Vex v = world.spawn(loc, Vex.class, e -> {
            e.setLimitedLifetime(false);
            e.setBound(bound);
            equip(e, false);
            setup(e, s.getDouble("health", 80), s.getDouble("damage", 8), "air");
        });
        homes.put(v.getUniqueId(), loc.clone());
    }

    private void equip(Mob e, boolean armor) {
        var eq = e.getEquipment();
        if (armor) {
            eq.setHelmet(new ItemStack(Material.NETHERITE_HELMET));
            eq.setChestplate(new ItemStack(Material.NETHERITE_CHESTPLATE));
            eq.setLeggings(new ItemStack(Material.NETHERITE_LEGGINGS));
            eq.setBoots(new ItemStack(Material.NETHERITE_BOOTS));
        }
        ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta meta = sword.getItemMeta();
        meta.addEnchant(Enchantment.DAMAGE_ALL, 5, true);
        meta.addEnchant(Enchantment.FIRE_ASPECT, 2, true);
        sword.setItemMeta(meta);
        eq.setItemInMainHand(sword);
        eq.setItemInOffHand(new ItemStack(Material.TOTEM_OF_UNDYING));
        for (EquipmentSlot slot : EquipmentSlot.values()) eq.setDropChance(slot, 0f);
    }

    private void setup(Mob e, double health, double damage, String type) {
        e.setCustomName(DraculaPlugin.color(plugin.getConfig().getString("guards.name")));
        e.setCustomNameVisible(true);
        e.setPersistent(true);
        e.setRemoveWhenFarAway(false);
        e.setCanPickupItems(false);
        setAttr(e, Attribute.GENERIC_MAX_HEALTH, health);
        e.setHealth(health);
        setAttr(e, Attribute.GENERIC_ATTACK_DAMAGE, damage - SWORD_BONUS);
        e.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, PotionEffect.INFINITE_DURATION, 0, false, false));
        e.getPersistentDataContainer().set(key, PersistentDataType.STRING, type);
    }

    private void setAttr(LivingEntity e, Attribute a, double v) {
        AttributeInstance inst = e.getAttribute(a);
        if (inst != null) inst.setBaseValue(Math.max(a == Attribute.GENERIC_ATTACK_DAMAGE ? 0 : 1, v));
    }

    // ------------------------------------------------------------ border / leash

    private boolean inBorder(Location l) {
        if (l.getWorld() != world) return false;
        int m = plugin.getConfig().getInt("guards.border-margin", 20);
        return l.getX() >= minX - m && l.getX() <= maxX + m
                && l.getZ() >= minZ - m && l.getZ() <= maxZ + m
                && l.getY() >= minY - 3 && l.getY() <= maxY + m + 10;
    }

    private void leashTick() {
        for (Map.Entry<UUID, Location> en : new ArrayList<>(homes.entrySet())) {
            Entity e = Bukkit.getEntity(en.getKey());
            if (e == null) continue;
            if (e.isDead()) { homes.remove(en.getKey()); continue; }
            if (e instanceof Mob mob) {
                LivingEntity t = mob.getTarget();
                if (t != null && !inBorder(t.getLocation())) mob.setTarget(null);
                if (!inBorder(mob.getLocation())) {
                    mob.setTarget(null);
                    mob.teleport(en.getValue());
                    world.spawnParticle(Particle.SMOKE_LARGE, en.getValue(), 15, 0.3, 0.5, 0.3, 0.02);
                }
            }
        }
    }

    // ------------------------------------------------------------ totem phase

    @EventHandler(ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent e) {
        LivingEntity g = e.getEntity();
        String type = g.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (type == null || type.endsWith("+")) return;
        g.getPersistentDataContainer().set(key, PersistentDataType.STRING, type + "+");

        // ванильный тотем ставит 1 HP — сразу после этого делаем стража сильнее
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (g.isDead()) return;
            ConfigurationSection s = plugin.getConfig().getConfigurationSection("guards.ground");
            double hp = s.getDouble("phase2-health", 60);
            setAttr(g, Attribute.GENERIC_MAX_HEALTH, hp);
            g.setHealth(hp);
            setAttr(g, Attribute.GENERIC_ATTACK_DAMAGE, s.getDouble("phase2-damage", 10) - SWORD_BONUS);
            g.setCustomName(DraculaPlugin.color(plugin.getConfig().getString("guards.name-phase2")));
            g.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, g.getLocation().add(0, 1, 0), 60, 0.5, 1, 0.5, 0.05);
            g.getWorld().playSound(g.getLocation(), Sound.ENTITY_WITHER_AMBIENT, 1.2f, 0.5f);
            for (Player p : g.getWorld().getPlayers()) {
                if (p.getLocation().distanceSquared(g.getLocation()) < 30 * 30) {
                    p.sendMessage(plugin.msg("messages.guard-enraged"));
                }
            }
        });
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        if (!e.getEntity().getPersistentDataContainer().has(key, PersistentDataType.STRING)) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
        homes.remove(e.getEntity().getUniqueId());
    }

    // ------------------------------------------------------------ cleanup

    public void clear() {
        if (leashTask != null) { leashTask.cancel(); leashTask = null; }
        for (UUID id : new ArrayList<>(homes.keySet())) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        homes.clear();
        if (world != null) {
            // подстраховка: убираем всех помеченных стражей в области башни (после рестарта и т.п.)
            for (Entity e : world.getEntities()) {
                if (e.getPersistentDataContainer().has(key, PersistentDataType.STRING)) e.remove();
            }
            for (long[] c : tickets) world.getChunkAt((int) c[0], (int) c[1]).removePluginChunkTicket(plugin);
        }
        tickets.clear();
    }
}
