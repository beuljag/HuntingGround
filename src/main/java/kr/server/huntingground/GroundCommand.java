package kr.server.huntingground;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Registry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

final class GroundCommand implements TabExecutor {

    private static final String PREFIX = "&a[사냥터] &f";
    private static final List<String> SET_OPTIONS = List.of("mob", "count", "radius", "respawn", "mode", "here");
    private static final List<String> MODES = List.of("EACH", "GROUP");

    private final HuntingGroundPlugin plugin;
    private final GroundData data;
    private final SpawnManager spawns;

    GroundCommand(HuntingGroundPlugin plugin, GroundData data, SpawnManager spawns) {
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
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "create" -> {
                    if (requireArgs(sender, args, 2, "create <사냥터>")) {
                        create(sender, args[1]);
                    }
                }
                case "delete" -> {
                    if (requireArgs(sender, args, 2, "delete <사냥터>")) {
                        delete(sender, args[1]);
                    }
                }
                case "list" -> send(sender, "사냥터: &e" + String.join(", ", data.grounds().keySet()));
                case "info" -> {
                    if (requireArgs(sender, args, 2, "info <사냥터>")) {
                        info(sender, args[1]);
                    }
                }
                case "add" -> {
                    if (requireArgs(sender, args, 5, "add <사냥터> <지점> <몹> <마리수> [반경] [리젠초] [EACH|GROUP]")) {
                        add(sender, args);
                    }
                }
                case "remove" -> {
                    if (requireArgs(sender, args, 3, "remove <사냥터> <지점>")) {
                        remove(sender, args[1], args[2]);
                    }
                }
                case "set" -> {
                    if (requireArgs(sender, args, 4, "set <사냥터> <지점> <항목> [값]")) {
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
                "create <사냥터> &7- 사냥터 생성",
                "delete <사냥터> &7- 사냥터 삭제 (소환된 몹도 사라짐)",
                "list | info <사냥터> &7- 사냥터 조회",
                "add <사냥터> <지점> <몹> <마리수> [반경] [리젠초] [EACH|GROUP] &7- 서 있는 곳에 스폰 지점 추가",
                "remove <사냥터> <지점> &7- 스폰 지점 삭제",
                "set <사냥터> <지점> <mob|count|radius|respawn|mode> <값> &7- 스폰 지점 수정",
                "set <사냥터> <지점> here &7- 스폰 지점을 서 있는 곳으로 옮김",
                "reset [사냥터] &7- 몹을 지우고 바로 다시 소환",
                "reload &7- 설정 다시 불러오기")) {
            sender.sendMessage(color("&e/" + label + " " + line));
        }
    }

    private void create(CommandSender sender, String id) throws IOException {
        if (!isValidId(sender, id)) {
            return;
        }
        if (data.grounds().containsKey(id)) {
            send(sender, "&c이미 있는 사냥터입니다: " + id);
            return;
        }
        data.grounds().put(id, new GroundData.Ground(id, new LinkedHashMap<>()));
        data.save();
        send(sender, "사냥터 &e" + id + "&f 생성 완료. 이제 add로 스폰 지점을 추가하세요.");
    }

    private void delete(CommandSender sender, String id) throws IOException {
        if (findGround(sender, id) == null) {
            return;
        }
        data.grounds().remove(id);
        data.save();
        spawns.sync();
        send(sender, "사냥터 &e" + id + "&f 삭제 완료.");
    }

    private void info(CommandSender sender, String id) {
        GroundData.Ground ground = findGround(sender, id);
        if (ground == null) {
            return;
        }
        send(sender, "&e" + id + "&f 스폰 지점 " + ground.points().size() + "개");
        for (GroundData.Point point : ground.points().values()) {
            sender.sendMessage(color(String.format(Locale.ROOT,
                    "&7- &f%s &7%s %d/%d마리, 반경 %.1f, 리젠 %d초(%s), %s %.0f %.0f %.0f",
                    point.id(), point.mob(), spawns.aliveCount(id, point.id()), point.count(), point.radius(),
                    point.respawnSeconds(), point.mode(), point.world(), point.x(), point.y(), point.z())));
        }
    }

    private void add(CommandSender sender, String[] args) throws IOException {
        if (!(sender instanceof Player player)) {
            send(sender, "&c플레이어만 사용할 수 있습니다.");
            return;
        }
        GroundData.Ground ground = findGround(sender, args[1]);
        if (ground == null || !isValidId(sender, args[2])) {
            return;
        }
        if (ground.points().containsKey(args[2])) {
            send(sender, "&c이미 있는 지점입니다. set으로 수정하거나 remove로 지우세요: " + args[2]);
            return;
        }
        String mob = parseMob(sender, args[3]);
        Integer count = parseCount(sender, args[4]);
        Double radius = args.length > 5 ? parseRadius(sender, args[5]) : Double.valueOf(5);
        Integer respawn = args.length > 6 ? parseRespawn(sender, args[6]) : Integer.valueOf(30);
        GroundData.RespawnMode mode = args.length > 7 ? parseMode(sender, args[7]) : GroundData.RespawnMode.EACH;
        if (mob == null || count == null || radius == null || respawn == null || mode == null) {
            return;
        }
        Location location = player.getLocation();
        ground.points().put(args[2], new GroundData.Point(args[2], location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ(), mob, count, radius, respawn, mode));
        data.save();
        spawns.sync();
        send(sender, "지점 &e" + args[2] + "&f 추가: &e" + mob + " " + count + "마리&f, 반경 " + radius
                + ", 리젠 " + respawn + "초(" + mode + ")");
    }

    private void remove(CommandSender sender, String groundId, String pointId) throws IOException {
        GroundData.Ground ground = findGround(sender, groundId);
        if (ground == null || findPoint(sender, ground, pointId) == null) {
            return;
        }
        ground.points().remove(pointId);
        data.save();
        spawns.sync();
        send(sender, "지점 &e" + pointId + "&f 삭제 완료.");
    }

    private void set(CommandSender sender, String[] args) throws IOException {
        GroundData.Ground ground = findGround(sender, args[1]);
        if (ground == null) {
            return;
        }
        GroundData.Point old = findPoint(sender, ground, args[2]);
        if (old == null) {
            return;
        }
        String option = args[3].toLowerCase(Locale.ROOT);
        if (!SET_OPTIONS.contains(option)) {
            send(sender, "&c항목은 " + SET_OPTIONS + " 중 하나입니다.");
            return;
        }
        if (!option.equals("here") && !requireArgs(sender, args, 5, "set <사냥터> <지점> " + option + " <값>")) {
            return;
        }
        GroundData.Point updated = updatedPoint(sender, old, option, args);
        if (updated == null) {
            return;
        }
        ground.points().put(old.id(), updated);
        data.save();
        // 값이 바뀐 지점은 sync에서 몹을 지우고 새 설정으로 다시 소환한다.
        spawns.sync();
        send(sender, "지점 &e" + old.id() + "&f의 &e" + option + "&f 변경 완료.");
    }

    private GroundData.Point updatedPoint(CommandSender sender, GroundData.Point old, String option, String[] args) {
        switch (option) {
            case "here" -> {
                if (!(sender instanceof Player player)) {
                    send(sender, "&c플레이어만 사용할 수 있습니다.");
                    return null;
                }
                Location location = player.getLocation();
                return new GroundData.Point(old.id(), location.getWorld().getName(), location.getX(), location.getY(),
                        location.getZ(), old.mob(), old.count(), old.radius(), old.respawnSeconds(), old.mode());
            }
            case "mob" -> {
                String mob = parseMob(sender, args[4]);
                return mob == null ? null : new GroundData.Point(old.id(), old.world(), old.x(), old.y(), old.z(),
                        mob, old.count(), old.radius(), old.respawnSeconds(), old.mode());
            }
            case "count" -> {
                Integer count = parseCount(sender, args[4]);
                return count == null ? null : new GroundData.Point(old.id(), old.world(), old.x(), old.y(), old.z(),
                        old.mob(), count, old.radius(), old.respawnSeconds(), old.mode());
            }
            case "radius" -> {
                Double radius = parseRadius(sender, args[4]);
                return radius == null ? null : new GroundData.Point(old.id(), old.world(), old.x(), old.y(), old.z(),
                        old.mob(), old.count(), radius, old.respawnSeconds(), old.mode());
            }
            case "respawn" -> {
                Integer respawn = parseRespawn(sender, args[4]);
                return respawn == null ? null : new GroundData.Point(old.id(), old.world(), old.x(), old.y(), old.z(),
                        old.mob(), old.count(), old.radius(), respawn, old.mode());
            }
            default -> {
                GroundData.RespawnMode mode = parseMode(sender, args[4]);
                return mode == null ? null : new GroundData.Point(old.id(), old.world(), old.x(), old.y(), old.z(),
                        old.mob(), old.count(), old.radius(), old.respawnSeconds(), mode);
            }
        }
    }

    private void reset(CommandSender sender, String[] args) {
        if (args.length > 1 && findGround(sender, args[1]) == null) {
            return;
        }
        spawns.reset(args.length > 1 ? args[1] : null);
        send(sender, "몹을 다시 소환합니다.");
    }

    /** 입력한 몹을 저장 형식으로 바꾼다. mythic:이름 또는 바닐라 이름을 받는다. */
    private String parseMob(CommandSender sender, String text) {
        if (text.toLowerCase(Locale.ROOT).startsWith(GroundData.MYTHIC_PREFIX)) {
            String name = text.substring(GroundData.MYTHIC_PREFIX.length());
            if (!isMythicEnabled()) {
                send(sender, "&cMythicMobs가 설치되어 있지 않습니다.");
                return null;
            }
            if (!MythicHook.exists(name)) {
                send(sender, "&c없는 미믹몹입니다: " + name + " &7(이름은 대소문자를 구분합니다)");
                return null;
            }
            return GroundData.MYTHIC_PREFIX + name;
        }
        EntityType type = GroundData.vanillaType(text);
        if (type == null) {
            send(sender, "&c소환할 수 없는 몹입니다: " + text);
            return null;
        }
        return type.name();
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

    private GroundData.RespawnMode parseMode(CommandSender sender, String text) {
        try {
            return GroundData.RespawnMode.valueOf(text.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            send(sender, "&c리젠 방식은 EACH(한 마리씩) 또는 GROUP(무리 전체)입니다.");
            return null;
        }
    }

    private boolean isMythicEnabled() {
        return Bukkit.getPluginManager().isPluginEnabled("MythicMobs");
    }

    private GroundData.Ground findGround(CommandSender sender, String id) {
        GroundData.Ground ground = data.grounds().get(id);
        if (ground == null) {
            send(sender, "&c없는 사냥터입니다: " + id);
        }
        return ground;
    }

    private GroundData.Point findPoint(CommandSender sender, GroundData.Ground ground, String id) {
        GroundData.Point point = ground.points().get(id);
        if (point == null) {
            send(sender, "&c없는 지점입니다: " + id);
        }
        return point;
    }

    private boolean isValidId(CommandSender sender, String id) {
        // 점은 YAML 경로 구분자이고, 슬래시는 내부에서 사냥터와 지점을 구분하는 데 쓴다.
        if (id.contains(".") || id.contains("/")) {
            send(sender, "&cID에는 점(.)과 슬래시(/)를 쓸 수 없습니다.");
            return false;
        }
        return true;
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
        String action = args[0].toLowerCase(Locale.ROOT);
        return switch (args.length) {
            case 1 -> match(args[0], List.of("create", "delete", "list", "info", "add", "remove", "set", "reset", "reload"));
            case 2 -> List.of("delete", "info", "add", "remove", "set", "reset").contains(action)
                    ? match(args[1], data.grounds().keySet())
                    : List.of();
            case 3 -> {
                GroundData.Ground ground = data.grounds().get(args[1]);
                yield ground != null && (action.equals("remove") || action.equals("set"))
                        ? match(args[2], ground.points().keySet())
                        : List.of();
            }
            case 4 -> switch (action) {
                case "add" -> match(args[3], mobNames());
                case "set" -> match(args[3], SET_OPTIONS);
                default -> List.of();
            };
            case 5 -> action.equals("set") ? switch (args[3].toLowerCase(Locale.ROOT)) {
                case "mob" -> match(args[4], mobNames());
                case "mode" -> match(args[4], MODES);
                default -> List.of();
            } : List.of();
            case 8 -> action.equals("add") ? match(args[7], MODES) : List.of();
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
            MythicHook.names().forEach(name -> names.add(GroundData.MYTHIC_PREFIX + name));
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
