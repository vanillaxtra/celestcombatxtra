package com.shyamstudio.celestcombatXtra.storage;

import com.shyamstudio.celestcombatXtra.CelestCombatPro;
import com.shyamstudio.celestcombatXtra.Scheduler;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class SqliteStorage implements PvpStorage {

    private final CelestCombatPro plugin;
    private HikariDataSource dataSource;
    private String tableName;

    public SqliteStorage(CelestCombatPro plugin) {
        this.plugin = plugin;
    }

    @Override
    public void init() {
        tableName = PvpSchema.resolveTableName(plugin.getConfig().getString("storage.table_prefix", ""));

        File dataDir = new File(plugin.getDataFolder(), "data");
        if (!dataDir.exists() && !dataDir.mkdirs()) {
            plugin.getLogger().warning("Failed to create data directory: " + dataDir.getAbsolutePath());
        }
        String fileName = plugin.getConfig().getString("storage.sqlite.file", "data/pvp.db");
        File dbFile = new File(plugin.getDataFolder(), fileName);
        if (!dbFile.getParentFile().exists()) {
            dbFile.getParentFile().mkdirs();
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        config.setDriverClassName(org.sqlite.JDBC.class.getName());
        // SQLite is single-writer; a pool larger than 1 causes SQLITE_BUSY under concurrent writes.
        int poolSize = Math.max(1, plugin.getConfig().getInt("storage.sqlite.pool_size", 1));
        config.setMaximumPoolSize(poolSize);
        config.setPoolName("CelestCombatXtra-SQLite");
        config.setConnectionInitSql("PRAGMA journal_mode=WAL;");

        dataSource = new HikariDataSource(config);

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(PvpSchema.createTableSqlite(tableName))) {
            statement.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to initialize SQLite pvp_state table", e);
        }
    }

    @Override
    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    @Override
    public CompletableFuture<Boolean> loadPvpState(UUID uuid) {
        return Scheduler.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(PvpSchema.selectStateSqlite(tableName))) {
                statement.setString(1, uuid.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return resultSet.getInt("pvp_enabled") != 0;
                    }
                    return null;
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load PVP state for " + uuid, e);
                return null;
            }
        });
    }

    @Override
    public CompletableFuture<Void> savePvpState(UUID uuid, boolean enabled) {
        return Scheduler.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(PvpSchema.upsertSqlite(tableName))) {
                statement.setString(1, uuid.toString());
                statement.setInt(2, enabled ? 1 : 0);
                statement.setLong(3, System.currentTimeMillis());
                statement.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save PVP state for " + uuid, e);
            }
            return null;
        });
    }
}
