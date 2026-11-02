package kr.server.huntingground;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class HuntingGroundPlugin extends JavaPlugin {

    private GroundData data;
    private SpawnManager spawns;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        data = new GroundData(this);
        data.load();

        spawns = new SpawnManager(this, data);
        getServer().getPluginManager().registerEvents(spawns, this);
        getServer().getScheduler().runTaskTimer(this, spawns, 20L, 20L);
        spawns.sync();

        GroundCommand command = new GroundCommand(this, data, spawns);
        PluginCommand registered = getCommand("huntingground");
        registered.setExecutor(command);
        registered.setTabCompleter(command);

        int points = data.grounds().values().stream().mapToInt(ground -> ground.points().size()).sum();
        getLogger().info("사냥터 " + data.grounds().size() + "개, 스폰 지점 " + points + "개를 불러왔습니다.");
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
