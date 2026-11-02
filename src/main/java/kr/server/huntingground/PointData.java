package kr.server.huntingground;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class PointData {

    static final String MYTHIC_PREFIX = "mythic:";

    enum RespawnMode {
        /** 죽은 몹마다 따로 리젠 시간을 잰다. */
        EACH,
        /** 무리가 전부 죽어야 리젠 시간을 재고, 한꺼번에 다시 나온다. */
        GROUP
    }

    record Point(String id, String world, double x, double y, double z, String mob,
                 int count, double radius, int respawnSeconds, RespawnMode mode) {

        boolean isMythic() {
            return mob.startsWith(MYTHIC_PREFIX);
        }

        String mythicName() {
            return mob.substring(MYTHIC_PREFIX.length());
        }

        /** 월드가 로드되어 있지 않으면 null을 돌려준다. */
        Location center() {
            World loaded = Bukkit.getWorld(world);
            return loaded != null ? new Location(loaded, x, y, z) : null;
        }
    }

    private final HuntingGroundPlugin plugin;
    private final File file;
    private final Map<String, Point> points = new LinkedHashMap<>();

    PointData(HuntingGroundPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "points.yml");
    }

    Map<String, Point> points() {
        return points;
    }

    void load() {
        points.clear();
        ConfigurationSection root = YamlConfiguration.loadConfiguration(file).getConfigurationSection("points");
        if (root == null) {
            return;
        }
        for (String id : root.getKeys(false)) {
            Point point = readPoint(id, root.getConfigurationSection(id));
            if (point != null) {
                points.put(id, point);
            }
        }
    }

    private Point readPoint(String id, ConfigurationSection section) {
        if (section == null) {
            plugin.getLogger().warning("리젠 지점 형식이 잘못되어 건너뜁니다: " + id);
            return null;
        }
        String mob = section.getString("mob", "");
        String world = section.getString("world");
        int count = section.getInt("count", 1);
        if (world == null || mob.isBlank() || count < 1) {
            plugin.getLogger().warning("world, mob, count를 확인하세요. 건너뜁니다: " + id);
            return null;
        }
        if (!mob.startsWith(MYTHIC_PREFIX) && vanillaType(mob) == null) {
            plugin.getLogger().warning("소환할 수 없는 몹이라 건너뜁니다: " + id + " (" + mob + ")");
            return null;
        }
        RespawnMode mode;
        try {
            mode = RespawnMode.valueOf(section.getString("mode", "EACH").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("mode는 EACH 또는 GROUP입니다. EACH로 읽습니다: " + id);
            mode = RespawnMode.EACH;
        }
        return new Point(id, world, section.getDouble("x"), section.getDouble("y"), section.getDouble("z"), mob,
                count, Math.max(0, section.getDouble("radius", 5)), Math.max(0, section.getInt("respawn", 30)), mode);
    }

    void save() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Point point : points.values()) {
            String path = "points." + point.id() + ".";
            yaml.set(path + "world", point.world());
            yaml.set(path + "x", point.x());
            yaml.set(path + "y", point.y());
            yaml.set(path + "z", point.z());
            yaml.set(path + "mob", point.mob());
            yaml.set(path + "count", point.count());
            yaml.set(path + "radius", point.radius());
            yaml.set(path + "respawn", point.respawnSeconds());
            yaml.set(path + "mode", point.mode().name());
        }
        yaml.save(file);
    }

    /** 바닐라 몹 이름을 EntityType으로 바꾼다. 소환할 수 없는 이름이면 null을 돌려준다. */
    static EntityType vanillaType(String name) {
        try {
            EntityType type = EntityType.valueOf(name.toUpperCase(Locale.ROOT));
            return type.isSpawnable() && type.isAlive() ? type : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
