package com.shyamstudio.celestcombatXtra.storage;

import com.shyamstudio.celestcombatXtra.CelestCombatPro;
import com.shyamstudio.celestcombatXtra.Scheduler;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class MariaDbStorage implements PvpStorage {

    private final CelestCombatPro plugin;
    private HikariDataSource dataSource;
    private String tableName;

    public MariaDbStorage(CelestCombatPro plugin) {
        this.plugin = plugin;
    }

    @Override
    public void init() {
        tableName = PvpSchema.resolveTableName(plugin.getConfig().getString("storage.table_prefix", ""));

        String host = plugin.getConfig().getString("storage.mariadb.host", "localhost");
        int port = plugin.getConfig().getInt("storage.mariadb.port", 3306);
        String database = plugin.getConfig().getString("storage.mariadb.database", "celestcombat");
        String username = plugin.getConfig().getString("storage.mariadb.username", "root");
        String password = plugin.getConfig().getString("storage.mariadb.password", "");
        int poolSize = Math.max(1, plugin.getConfig().getInt("storage.mariadb.pool_size", 10));

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mariadb://" + host + ":" + port + "/" + database);
        config.setDriverClassName(org.mariadb.jdbc.Driver.class.getName());
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(poolSize);
        config.setPoolName("CelestCombatXtra-MariaDB");

        dataSource = new HikariDataSource(config);

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(PvpSchema.createTableMariadb(tableName))) {
            statement.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to initialize MariaDB pvp_state table", e);
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
                 PreparedStatement statement = connection.prepareStatement(PvpSchema.selectStateMariadb(tableName))) {
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
                 PreparedStatement statement = connection.prepareStatement(PvpSchema.upsertMariadb(tableName))) {
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
