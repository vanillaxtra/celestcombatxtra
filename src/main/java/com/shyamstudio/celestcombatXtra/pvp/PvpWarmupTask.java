package com.shyamstudio.celestcombatXtra.pvp;

import com.shyamstudio.celestcombatXtra.Scheduler;

/**
 * Bookkeeping for a single player's in-progress activation/deactivation warmup.
 * At most one of these exists per player at a time - starting a new warmup always
 * cancels and replaces any existing one.
 */
public class PvpWarmupTask {
    private final Scheduler.Task task;
    private final PvpState targetState;
    private final boolean teleportTriggered;
    private final long startTimeMillis;
    private final long durationTicks;

    public PvpWarmupTask(Scheduler.Task task, PvpState targetState, boolean teleportTriggered, long durationTicks) {
        this.task = task;
        this.targetState = targetState;
        this.teleportTriggered = teleportTriggered;
        this.startTimeMillis = System.currentTimeMillis();
        this.durationTicks = durationTicks;
    }

    public void cancel() {
        task.cancel();
    }

    public PvpState getTargetState() {
        return targetState;
    }

    public boolean isTeleportTriggered() {
        return teleportTriggered;
    }

    public long getStartTimeMillis() {
        return startTimeMillis;
    }

    public long getDurationTicks() {
        return durationTicks;
    }
}
