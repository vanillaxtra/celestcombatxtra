package com.shyamstudio.celestcombatXtra.storage;

import com.shyamstudio.celestcombatXtra.CelestCombatPro;

public final class StorageFactory {
    private StorageFactory() {}

    public static PvpStorage create(CelestCombatPro plugin) {
        String type = plugin.getConfig().getString("storage.type", "sqlite");
        PvpStorage storage = "mariadb".equalsIgnoreCase(type)
                ? new MariaDbStorage(plugin)
                : new SqliteStorage(plugin);
        storage.init();
        return storage;
    }
}
