package com.shyamstudio.celestcombatXtra.storage;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Persists per-player PVP toggle state. Implementations must perform all I/O
 * off the calling thread and never block the caller.
 */
public interface PvpStorage {

    /**
     * Prepares the backing connection pool/schema. Safe to call once during enable.
     */
    void init();

    /**
     * Closes the backing connection pool. Safe to call during disable even if init() failed.
     */
    void shutdown();

    /**
     * Resolves to the stored PVP state for the player, or {@code null} if no row exists
     * (caller should fall back to the configured server default).
     */
    CompletableFuture<Boolean> loadPvpState(UUID uuid);

    /**
     * Upserts the player's PVP state.
     */
    CompletableFuture<Void> savePvpState(UUID uuid, boolean enabled);
}
