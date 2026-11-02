package kr.server.huntingground;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class HuntingGroundPlugin extends JavaPlugin {

    private PointData data;
    private SpawnManager spawns;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        data = new PointData(this);
        data.load();

        spawns = new SpawnManager(this, data);
        getServer().getPluginManager().registerEvents(spawns, this);
        getServer().getScheduler().runTaskTimer(this, spawns, 20L, 20L);
        spawns.sync();

        RegenCommand command = new RegenCommand(this, data, spawns);
        PluginCommand registered = getCommand("huntingground");
        registered.setExecutor(command);
        registered.setTabCompleter(command);

        getLogger().info("사냥터 지점 " + data.points().size() + "개를 불러왔습니다.");
    }

    @Override
    public void onDisable() {
        if (spawns != null) {
            spawns.removeAll();
        }
    }

    void reload() {
        reloadConfig();
        data.load();
        spawns.sync();
    }
}
