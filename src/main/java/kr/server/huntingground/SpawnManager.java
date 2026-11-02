package kr.server.huntingground;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** 리젠 지점마다 몹을 채우고, 그 몹의 드롭템에 주인을 지정한다. */
final class SpawnManager implements Listener, Runnable {

    /** 사망 위치에서 이 거리 안에 생긴 아이템을 그 몹의 드롭템으로 본다. */
    private static final double DROP_RANGE_SQUARED = 3 * 3;
    /** 사망 후 이 틱 수 안에 생긴 아이템까지 드롭템으로 본다. */
    private static final int DROP_WINDOW_TICKS = 2;
    private static final int SPOT_ATTEMPTS = 10;
    private static final int SPOT_HEIGHT_RANGE = 3;

    private static final class PointState {
        private PointData.Point point;
        private final Set<UUID> alive = new HashSet<>();
        /** 비어 있는 자리마다 리젠할 시각(틱). alive 수와 합치면 항상 마리 수와 같다. */
        private final List<Long> respawnAt = new ArrayList<>();
        private boolean warned;

        private PointState(PointData.Point point) {
            this.point = point;
        }
    }

    private record RecentKill(UUID killer, Location location, long expiresAt) {
    }

    private final HuntingGroundPlugin plugin;
    private final PointData data;
    private final NamespacedKey ownedKey;
    private final Map<String, PointState> states = new HashMap<>();
    private final Set<UUID> tracked = new HashSet<>();
    private final List<RecentKill> recentKills = new ArrayList<>();

    SpawnManager(HuntingGroundPlugin plugin, PointData data) {
        this.plugin = plugin;
        this.data = data;
        this.ownedKey = new NamespacedKey(plugin, "owned_drop");
    }

    /** 설정과 실제 상태를 맞춘다. 바뀌지 않은 지점의 몹은 그대로 둔다. */
    void sync() {
        Map<String, PointData.Point> wanted = data.points();

        Iterator<Map.Entry<String, PointState>> iterator = states.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, PointState> entry = iterator.next();
            PointData.Point point = wanted.get(entry.getKey());
            if (point == null) {
                clear(entry.getValue());
                iterator.remove();
            } else if (!point.equals(entry.getValue().point)) {
                entry.getValue().point = point;
                refill(entry.getValue());
            }
        }
        wanted.forEach((id, point) -> states.computeIfAbsent(id, ignored -> {
            PointState state = new PointState(point);
            refill(state);
            return state;
        }));
    }

    int aliveCount(String id) {
        PointState state = states.get(id);
        return state != null ? state.alive.size() : 0;
    }

    /** 지점의 몹을 지우고 바로 다시 소환한다. id가 null이면 전체가 대상이다. */
    void reset(String id) {
        states.forEach((stateId, state) -> {
            if (id == null || stateId.equals(id)) {
                refill(state);
            }
        });
    }

    void removeAll() {
        states.values().forEach(this::clear);
        states.clear();
    }

    private void refill(PointState state) {
        clear(state);
        long now = Bukkit.getCurrentTick();
        for (int i = 0; i < state.point.count(); i++) {
            state.respawnAt.add(now);
        }
    }

    private void clear(PointState state) {
        for (UUID id : state.alive) {
            tracked.remove(id);
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) {
                entity.remove();
            }
        }
        state.alive.clear();
        state.respawnAt.clear();
        state.warned = false;
    }

    @Override
    public void run() {
        long now = Bukkit.getCurrentTick();
        double leashRange = plugin.getConfig().getDouble("leash-range", 40);
        double activationRange = plugin.getConfig().getDouble("activation-range", 64);
        for (PointState state : states.values()) {
            collectGone(state, now, leashRange);
            spawnDue(state, now, activationRange);
        }
    }

    private void collectGone(PointState state, long now, double leashRange) {
        Location center = state.point.center();
        int gone = 0;
        Iterator<UUID> iterator = state.alive.iterator();
        while (iterator.hasNext()) {
            UUID id = iterator.next();
            Entity entity = Bukkit.getEntity(id);
            if (entity == null || !entity.isValid()) {
                if (entity != null && !entity.isDead()) {
                    entity.remove();
                }
                iterator.remove();
                tracked.remove(id);
                gone++;
            } else if (center != null && leashRange > 0 && isOutOfLeash(entity, center, leashRange)) {
                if (entity instanceof Mob mob) {
                    mob.setTarget(null);
                }
                entity.teleport(center);
            }
        }
        if (gone == 0) {
            return;
        }
        long respawnTime = now + state.point.respawnSeconds() * 20L;
        if (state.point.mode() == PointData.RespawnMode.EACH) {
            for (int i = 0; i < gone; i++) {
                state.respawnAt.add(respawnTime);
            }
            return;
        }
        // 무리 리젠: 마지막 한 마리가 죽을 때까지 빈자리는 시간을 재지 않는다.
        for (int i = 0; i < gone; i++) {
            state.respawnAt.add(Long.MAX_VALUE);
        }
        if (state.alive.isEmpty()) {
            state.respawnAt.replaceAll(ignored -> respawnTime);
        }
    }

    private boolean isOutOfLeash(Entity entity, Location center, double leashRange) {
        return !entity.getWorld().equals(center.getWorld())
                || entity.getLocation().distanceSquared(center) > leashRange * leashRange;
    }

    private void spawnDue(PointState state, long now, double activationRange) {
        if (state.respawnAt.stream().noneMatch(time -> time <= now)) {
            return;
        }
        Location center = state.point.center();
        if (center == null || !center.isChunkLoaded() || !isPlayerNear(center, activationRange)) {
            return;
        }
        Iterator<Long> iterator = state.respawnAt.iterator();
        while (iterator.hasNext()) {
            if (iterator.next() > now) {
                continue;
            }
            Entity entity = spawn(state.point, findSpot(center, state.point.radius()));
            if (entity == null || !entity.isValid()) {
                if (!state.warned) {
                    state.warned = true;
                    plugin.getLogger().warning("몹을 소환하지 못했습니다: " + state.point.id() + " (" + state.point.mob()
                            + "). 몹 이름과 스폰을 막는 플러그인을 확인하세요. 계속 다시 시도합니다.");
                }
                return;
            }
            state.warned = false;
            // 서버를 재시작해도 청크에 저장되지 않게 해서 몹이 쌓이는 것을 막는다.
            entity.setPersistent(false);
            if (entity instanceof LivingEntity living) {
                living.setRemoveWhenFarAway(false);
            }
            state.alive.add(entity.getUniqueId());
            tracked.add(entity.getUniqueId());
            iterator.remove();
        }
    }

    private boolean isPlayerNear(Location center, double range) {
        if (range <= 0) {
            return true;
        }
        for (Player player : center.getWorld().getPlayers()) {
            if (player.getGameMode() != GameMode.SPECTATOR
                    && player.getLocation().distanceSquared(center) <= range * range) {
                return true;
            }
        }
        return false;
    }

    private Entity spawn(PointData.Point point, Location location) {
        if (point.isMythic()) {
            return Bukkit.getPluginManager().isPluginEnabled("MythicMobs")
                    ? MythicHook.spawn(point.mythicName(), location)
                    : null;
        }
        return location.getWorld().spawnEntity(location, PointData.vanillaType(point.mob()),
                CreatureSpawnEvent.SpawnReason.CUSTOM);
    }

    /** 반경 안에서 서 있을 수 있는 자리를 찾는다. 못 찾으면 지점 중심을 돌려준다. */
    private Location findSpot(Location center, double radius) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        World world = center.getWorld();
        for (int attempt = 0; attempt < SPOT_ATTEMPTS; attempt++) {
            double angle = random.nextDouble(Math.PI * 2);
            // 제곱근을 써야 원 안에 고르게 퍼진다.
            double distance = Math.sqrt(random.nextDouble()) * radius;
            int x = (int) Math.floor(center.getX() + Math.cos(angle) * distance);
            int z = (int) Math.floor(center.getZ() + Math.sin(angle) * distance);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            for (int dy = SPOT_HEIGHT_RANGE; dy >= -SPOT_HEIGHT_RANGE; dy--) {
                int y = center.getBlockY() + dy;
                if (canStand(world, x, y, z)) {
                    return new Location(world, x + 0.5, y, z + 0.5, random.nextFloat() * 360, 0);
                }
            }
        }
        return center.clone();
    }

    private boolean canStand(World world, int x, int y, int z) {
        Block feet = world.getBlockAt(x, y, z);
        Block head = world.getBlockAt(x, y + 1, z);
        return world.getBlockAt(x, y - 1, z).getType().isSolid()
                && feet.isPassable() && !feet.isLiquid()
                && head.isPassable() && !head.isLiquid();
    }

    // 다른 플러그인(MythicMobs 포함)이 드롭템을 만들기 전에 잡은 사람을 기록해 둔다.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer != null && tracked.contains(event.getEntity().getUniqueId())) {
            recentKills.add(new RecentKill(killer.getUniqueId(), event.getEntity().getLocation(),
                    Bukkit.getCurrentTick() + DROP_WINDOW_TICKS));
        }
    }

    // 바닐라 드롭과 MythicMobs 드롭 테이블을 같은 방식으로 처리할 수 있도록 생성되는 아이템을 잡는다.
    @EventHandler(ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (recentKills.isEmpty()) {
            return;
        }
        long now = Bukkit.getCurrentTick();
        recentKills.removeIf(kill -> kill.expiresAt() < now);
        Item item = event.getEntity();
        // 플레이어가 버린 아이템은 드롭템이 아니다.
        if (item.getThrower() != null) {
            return;
        }
        // 드롭템은 사망 직후에 생기므로 가장 최근 처치부터 맞춰 봐야 붙어서 싸우는 두 사람이 뒤바뀌지 않는다.
        for (int i = recentKills.size() - 1; i >= 0; i--) {
            RecentKill kill = recentKills.get(i);
            if (kill.location().getWorld().equals(item.getWorld())
                    && kill.location().distanceSquared(item.getLocation()) <= DROP_RANGE_SQUARED) {
                item.setOwner(kill.killer());
                item.setCanMobPickup(false);
                item.getPersistentDataContainer().set(ownedKey, PersistentDataType.BYTE, (byte) 1);
                return;
            }
        }
    }

    // 호퍼는 아이템 주인을 확인하지 않으므로 따로 막는다.
    @EventHandler(ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent event) {
        if (event.getItem().getPersistentDataContainer().has(ownedKey)) {
            event.setCancelled(true);
        }
    }
}
