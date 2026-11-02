package kr.server.huntingground;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

final class RegenCommand implements TabExecutor {

    private static final String PREFIX = "&a[사냥터] &f";
    private static final String ADD_USAGE = "<x> <y> <z> <몹> [마리수] [반경] [리젠초] [EACH|GROUP]";
    private static final List<String> SET_OPTIONS = List.of("mob", "count", "radius", "respawn", "mode", "here");
    private static final List<String> MODES = List.of("EACH", "GROUP");

    private final HuntingGroundPlugin plugin;
    private final PointData data;
    private final SpawnManager spawns;

    RegenCommand(HuntingGroundPlugin plugin, PointData data, SpawnManager spawns) {
        this.plugin = plugin;
        this.data = data;
        this.spawns = spawns;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            help(sender, label);
            return true;
        }
        try {
            if (isCoordinate(args[0])) {
                if (requireArgs(sender, args, 4, ADD_USAGE)) {
                    add(sender, args);
                }
                return true;
            }
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "remove" -> {
                    if (requireArgs(sender, args, 2, "remove <번호>")) {
                        remove(sender, args[1]);
                    }
                }
                case "list" -> list(sender);
                case "set" -> {
                    if (requireArgs(sender, args, 3, "set <번호> <항목> [값]")) {
                        set(sender, args);
                    }
                }
                case "reset" -> reset(sender, args);
                case "reload" -> {
                    plugin.reload();
                    send(sender, "설정을 다시 불러왔습니다.");
                }
                default -> help(sender, label);
            }
        } catch (IOException e) {
            plugin.getLogger().severe("파일 저장 실패: " + e);
            send(sender, "&c파일 저장에 실패했습니다. 콘솔을 확인하세요.");
        }
        return true;
    }

    private void help(CommandSender sender, String label) {
        send(sender, "명령어 목록");
        for (String line : List.of(
                ADD_USAGE + " &7- 지점 추가 (좌표에 ~ 사용 가능)",
                "remove <번호> &7- 지점 삭제 (소환된 몹도 사라짐)",
                "list &7- 지점 목록과 번호",
                "set <번호> <mob|count|radius|respawn|mode> <값> &7- 지점 수정",
                "set <번호> here &7- 지점을 서 있는 곳으로 옮김",
                "reset [번호] &7- 몹을 지우고 바로 다시 소환",
                "reload &7- 설정 다시 불러오기")) {
            sender.sendMessage(color("&e/" + label + " " + line));
        }
    }

    private void list(CommandSender sender) {
        send(sender, "지점 " + data.points().size() + "개");
        for (PointData.Point point : data.points().values()) {
            sender.sendMessage(color(String.format(Locale.ROOT,
                    "&7- &f%s &7%s %d/%d마리, 반경 %.1f, 리젠 %d초(%s), %s %.0f %.0f %.0f",
                    point.id(), point.mob(), spawns.aliveCount(point.id()), point.count(), point.radius(),
                    point.respawnSeconds(), point.mode(), point.world(), point.x(), point.y(), point.z())));
        }
    }

    private void add(CommandSender sender, String[] args) throws IOException {
        Location base = senderLocation(sender);
        Double x = parseCoordinate(args[0], base == null ? null : base.getX(), true);
        Double y = parseCoordinate(args[1], base == null ? null : base.getY(), false);
        Double z = parseCoordinate(args[2], base == null ? null : base.getZ(), true);
        if (x == null || y == null || z == null) {
            send(sender, "&c좌표는 숫자 또는 ~ 형식입니다. 콘솔에서는 ~를 쓸 수 없습니다.");
            return;
        }
        // 콘솔처럼 위치가 없는 곳에서 입력하면 기본 월드를 쓴다.
        World world = base != null ? base.getWorld() : Bukkit.getWorlds().get(0);
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            send(sender, "&cy 좌표는 " + world.getMinHeight() + " 이상 " + world.getMaxHeight() + " 미만이어야 합니다.");
            return;
        }
        String mob = parseMob(sender, args[3]);
        Integer count = args.length > 4 ? parseCount(sender, args[4]) : Integer.valueOf(1);
        Double radius = args.length > 5 ? parseRadius(sender, args[5]) : Double.valueOf(5);
        Integer respawn = args.length > 6 ? parseRespawn(sender, args[6]) : Integer.valueOf(30);
        PointData.RespawnMode mode = args.length > 7 ? parseMode(sender, args[7]) : PointData.RespawnMode.EACH;
        if (mob == null || count == null || radius == null || respawn == null || mode == null) {
            return;
        }
        String id = nextId();
        data.points().put(id, new PointData.Point(id, world.getName(), x, y, z, mob, count, radius, respawn, mode));
        data.save();
        spawns.sync();
        send(sender, String.format(Locale.ROOT, "지점 &e%s&f 추가: &e%s %d마리&f, %s %.1f %.1f %.1f, 반경 %.1f, 리젠 %d초(%s)",
                id, mob, count, world.getName(), x, y, z, radius, respawn, mode));
    }

    private String nextId() {
        int id = 1;
        while (data.points().containsKey(String.valueOf(id))) {
            id++;
        }
        return String.valueOf(id);
    }

    private Location senderLocation(CommandSender sender) {
        if (sender instanceof Entity entity) {
            return entity.getLocation();
        }
        if (sender instanceof BlockCommandSender block) {
            return block.getBlock().getLocation().add(0.5, 0, 0.5);
        }
        return null;
    }

    private boolean isCoordinate(String text) {
        return text.startsWith("~") || parseNumber(text) != null;
    }

    /** 숫자, ~, ~숫자를 좌표로 바꾼다. 형식이 틀렸거나 기준 위치 없이 ~를 쓰면 null을 돌려준다. */
    private Double parseCoordinate(String text, Double base, boolean centerOnBlock) {
        if (text.startsWith("~")) {
            Double offset = text.length() == 1 ? Double.valueOf(0) : parseNumber(text.substring(1));
            return base == null || offset == null ? null : Double.valueOf(base + offset);
        }
        Double value = parseNumber(text);
        // 바닐라 명령어처럼 소수점 없는 x, z는 블록 가운데로 맞춘다.
        return value != null && centerOnBlock && !text.contains(".") ? Double.valueOf(value + 0.5) : value;
    }

    private Double parseNumber(String text) {
        try {
            double value = Double.parseDouble(text);
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void remove(CommandSender sender, String id) throws IOException {
        if (findPoint(sender, id) == null) {
            return;
        }
        data.points().remove(id);
        data.save();
        spawns.sync();
        send(sender, "지점 &e" + id + "&f 삭제 완료.");
    }

    private void set(CommandSender sender, String[] args) throws IOException {
        PointData.Point old = findPoint(sender, args[1]);
        if (old == null) {
            return;
        }
        String option = args[2].toLowerCase(Locale.ROOT);
        if (!SET_OPTIONS.contains(option)) {
            send(sender, "&c항목은 " + SET_OPTIONS + " 중 하나입니다.");
            return;
        }
        if (!option.equals("here") && !requireArgs(sender, args, 4, "set <번호> " + option + " <값>")) {
            return;
        }
        PointData.Point updated = updatedPoint(sender, old, option, args);
        if (updated == null) {
            return;
        }
        data.points().put(old.id(), updated);
        data.save();
        // 값이 바뀐 지점은 sync에서 몹을 지우고 새 설정으로 다시 소환한다.
        spawns.sync();
        send(sender, "지점 &e" + old.id() + "&f의 &e" + option + "&f 변경 완료.");
    }

    private PointData.Point updatedPoint(CommandSender sender, PointData.Point old, String option, String[] args) {
        switch (option) {
            case "here" -> {
                if (!(sender instanceof Player player)) {
                    send(sender, "&c플레이어만 사용할 수 있습니다.");
                    return null;
                }
                Location location = player.getLocation();
                return new PointData.Point(old.id(), location.getWorld().getName(), location.getX(), location.getY(),
                        location.getZ(), old.mob(), old.count(), old.radius(), old.respawnSeconds(), old.mode());
            }
            case "mob" -> {
                String mob = parseMob(sender, args[3]);
                return mob == null ? null : new PointData.Point(old.id(), old.world(), old.x(), old.y(), old.z(),
                        mob, old.count(), old.radius(), old.respawnSeconds(), old.mode());
            }
            case "count" -> {
                Integer count = parseCount(sender, args[3]);
                return count == null ? null : new PointData.Point(old.id(), old.world(), old.x(), old.y(), old.z(),
                        old.mob(), count, old.radius(), old.respawnSeconds(), old.mode());
            }
            case "radius" -> {
                Double radius = parseRadius(sender, args[3]);
                return radius == null ? null : new PointData.Point(old.id(), old.world(), old.x(), old.y(), old.z(),
                        old.mob(), old.count(), radius, old.respawnSeconds(), old.mode());
            }
            case "respawn" -> {
                Integer respawn = parseRespawn(sender, args[3]);
                return respawn == null ? null : new PointData.Point(old.id(), old.world(), old.x(), old.y(), old.z(),
                        old.mob(), old.count(), old.radius(), respawn, old.mode());
            }
            default -> {
                PointData.RespawnMode mode = parseMode(sender, args[3]);
                return mode == null ? null : new PointData.Point(old.id(), old.world(), old.x(), old.y(), old.z(),
                        old.mob(), old.count(), old.radius(), old.respawnSeconds(), mode);
            }
        }
    }

    private void reset(CommandSender sender, String[] args) {
        if (args.length > 1 && findPoint(sender, args[1]) == null) {
            return;
        }
        spawns.reset(args.length > 1 ? args[1] : null);
        send(sender, "몹을 다시 소환합니다.");
    }

    /** 입력한 몹을 저장 형식으로 바꾼다. 기본 몹 이름을 먼저 찾고, 없으면 미믹몹에서 찾는다. mythic:을 붙이면 미믹몹만 찾는다. */
    private String parseMob(CommandSender sender, String text) {
        if (text.toLowerCase(Locale.ROOT).startsWith(PointData.MYTHIC_PREFIX)) {
            String name = text.substring(PointData.MYTHIC_PREFIX.length());
            if (!isMythicEnabled()) {
                send(sender, "&cMythicMobs가 설치되어 있지 않습니다.");
                return null;
            }
            if (!MythicHook.exists(name)) {
                send(sender, "&c없는 미믹몹입니다: " + name + " &7(이름은 대소문자를 구분합니다)");
                return null;
            }
            return PointData.MYTHIC_PREFIX + name;
        }
        EntityType type = PointData.vanillaType(text);
        if (type != null) {
            return type.name();
        }
        if (isMythicEnabled() && MythicHook.exists(text)) {
            return PointData.MYTHIC_PREFIX + text;
        }
        send(sender, "&c소환할 수 없는 몹입니다: " + text + " &7(미믹몹 이름은 대소문자를 구분합니다)");
        return null;
    }

    private Integer parseCount(CommandSender sender, String text) {
        try {
            int value = Integer.parseInt(text);
            if (value >= 1) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // 아래에서 안내한다.
        }
        send(sender, "&c마리 수는 1 이상의 정수여야 합니다.");
        return null;
    }

    private Double parseRadius(CommandSender sender, String text) {
        try {
            double value = Double.parseDouble(text);
            if (value >= 0 && Double.isFinite(value)) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // 아래에서 안내한다.
        }
        send(sender, "&c반경은 0 이상의 숫자여야 합니다.");
        return null;
    }

    private Integer parseRespawn(CommandSender sender, String text) {
        try {
            int value = Integer.parseInt(text);
            if (value >= 0) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // 아래에서 안내한다.
        }
        send(sender, "&c리젠 시간은 0 이상의 정수(초)여야 합니다.");
        return null;
    }

    private PointData.RespawnMode parseMode(CommandSender sender, String text) {
        try {
            return PointData.RespawnMode.valueOf(text.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            send(sender, "&c리젠 방식은 EACH(한 마리씩) 또는 GROUP(무리 전체)입니다.");
            return null;
        }
    }

    private boolean isMythicEnabled() {
        return Bukkit.getPluginManager().isPluginEnabled("MythicMobs");
    }

    private PointData.Point findPoint(CommandSender sender, String id) {
        PointData.Point point = data.points().get(id);
        if (point == null) {
            send(sender, "&c없는 지점입니다: " + id);
        }
        return point;
    }

    private boolean requireArgs(CommandSender sender, String[] args, int count, String usage) {
        if (args.length < count) {
            send(sender, "&c/사냥터 " + usage);
            return false;
        }
        return true;
    }

    private void send(CommandSender sender, String message) {
        sender.sendMessage(color(PREFIX + message));
    }

    private Component color(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length > 1 && isCoordinate(args[0])) {
            return switch (args.length) {
                case 2, 3 -> match(args[args.length - 1], List.of("~"));
                case 4 -> match(args[3], mobNames());
                case 8 -> match(args[7], MODES);
                default -> List.of();
            };
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        return switch (args.length) {
            case 1 -> match(args[0], List.of("~", "remove", "list", "set", "reset", "reload"));
            case 2 -> List.of("remove", "set", "reset").contains(action)
                    ? match(args[1], data.points().keySet())
                    : List.of();
            case 3 -> action.equals("set") ? match(args[2], SET_OPTIONS) : List.of();
            case 4 -> action.equals("set") ? switch (args[2].toLowerCase(Locale.ROOT)) {
                case "mob" -> match(args[3], mobNames());
                case "mode" -> match(args[3], MODES);
                default -> List.of();
            } : List.of();
            default -> List.of();
        };
    }

    private List<String> mobNames() {
        List<String> names = new ArrayList<>();
        Registry.ENTITY_TYPE.forEach(type -> {
            if (type.isSpawnable() && type.isAlive()) {
                names.add(type.name());
            }
        });
        if (isMythicEnabled()) {
            names.addAll(MythicHook.names());
        }
        return names;
    }

    private List<String> match(String prefix, Collection<String> options) {
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
                result.add(option);
            }
        }
        return result;
    }
}
