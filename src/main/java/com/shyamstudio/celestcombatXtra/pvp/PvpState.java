package com.shyamstudio.celestcombatXtra.pvp;

/**
 * PVP toggle state machine.
 *
 * Effective PVP-enabled (used for damage checks) is true only in {@link #ON} and
 * {@link #DEACTIVATION_WARMUP} - a deactivating player is still fully fightable until
 * their warmup completes, which is what makes "getting tagged cancels the deactivation"
 * meaningful. {@link #ACTIVATION_WARMUP} counts as off - a player arming PVP cannot yet
 * be hit or hit others.
 */
public enum PvpState {
    OFF,
    ACTIVATION_WARMUP,
    ON,
    DEACTIVATION_WARMUP;

    public boolean isEffectivelyEnabled() {
        return this == ON || this == DEACTIVATION_WARMUP;
    }
}
