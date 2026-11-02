package kr.server.huntingground;

import io.lumine.mythic.api.exceptions.InvalidMobTypeException;
import io.lumine.mythic.bukkit.MythicBukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.Collection;

/** MythicMobs가 있을 때만 호출해야 한다. 없을 때 이 클래스를 건드리면 NoClassDefFoundError가 난다. */
final class MythicHook {

    private MythicHook() {
    }

    static boolean exists(String name) {
        return MythicBukkit.inst().getMobManager().getMythicMob(name).isPresent();
    }

    static Collection<String> names() {
        return MythicBukkit.inst().getMobManager().getMobNames();
    }

    static Entity spawn(String name, Location location) {
        try {
            return MythicBukkit.inst().getAPIHelper().spawnMythicMob(name, location);
        } catch (InvalidMobTypeException e) {
            return null;
        }
    }
}
