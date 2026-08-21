package com.shyamstudio.celestcombatXtra.storage;

/**
 * Table name resolution (with configurable prefix) and per-dialect SQL for the
 * pvp state table.
 *
 * Table identifiers can't be bound as PreparedStatement parameters, so the
 * configured prefix is sanitized down to a safe identifier charset before being
 * concatenated into SQL - this both prevents SQL injection via a malicious/broken
 * config value and normalizes admin-friendly inputs like a server name ("hello-world",
 * "hello world") into a valid, readable table name ("hello_world_pvp_state").
 */
final class PvpSchema {
    private PvpSchema() {}

    private static final String BASE_TABLE = "pvp_state";

    /**
     * Resolves the configured prefix into a safe table name.
     * Any run of characters outside [a-zA-Z0-9_] is collapsed to a single
     * underscore, leading/trailing underscores are trimmed, and (if non-empty)
     * a single trailing underscore is (re-)appended as the separator before
     * "pvp_state". A blank/empty prefix results in no prefix at all.
     *
     * Examples: "hello-world" -> "hello_world_pvp_state"
     *           "hello_world" -> "hello_world_pvp_state"
     *           "hello_world_" -> "hello_world_pvp_state"
     *           ""  / null    -> "pvp_state"
     */
    static String resolveTableName(String configuredPrefix) {
        if (configuredPrefix == null || configuredPrefix.isBlank()) {
            return BASE_TABLE;
        }

        String sanitized = configuredPrefix.replaceAll("[^a-zA-Z0-9_]+", "_");
        sanitized = sanitized.replaceAll("^_+", "").replaceAll("_+$", "");

        if (sanitized.isEmpty()) {
            return BASE_TABLE;
        }

        return sanitized + "_" + BASE_TABLE;
    }

    static String createTableSqlite(String tableName) {
        return "CREATE TABLE IF NOT EXISTS \"" + tableName + "\" (\n"
                + "    player_uuid TEXT PRIMARY KEY,\n"
                + "    pvp_enabled INTEGER NOT NULL,\n"
                + "    updated_at BIGINT NOT NULL\n"
                + ")";
    }

    static String createTableMariadb(String tableName) {
        return "CREATE TABLE IF NOT EXISTS `" + tableName + "` (\n"
                + "    player_uuid VARCHAR(36) PRIMARY KEY,\n"
                + "    pvp_enabled TINYINT(1) NOT NULL,\n"
                + "    updated_at BIGINT NOT NULL\n"
                + ")";
    }

    static String selectStateSqlite(String tableName) {
        return "SELECT pvp_enabled FROM \"" + tableName + "\" WHERE player_uuid = ?";
    }

    static String selectStateMariadb(String tableName) {
        return "SELECT pvp_enabled FROM `" + tableName + "` WHERE player_uuid = ?";
    }

    static String upsertSqlite(String tableName) {
        return "INSERT INTO \"" + tableName + "\" (player_uuid, pvp_enabled, updated_at) VALUES (?, ?, ?)\n"
                + "ON CONFLICT(player_uuid) DO UPDATE SET pvp_enabled = excluded.pvp_enabled, updated_at = excluded.updated_at";
    }

    static String upsertMariadb(String tableName) {
        return "INSERT INTO `" + tableName + "` (player_uuid, pvp_enabled, updated_at) VALUES (?, ?, ?)\n"
                + "ON DUPLICATE KEY UPDATE pvp_enabled = VALUES(pvp_enabled), updated_at = VALUES(updated_at)";
    }
}
