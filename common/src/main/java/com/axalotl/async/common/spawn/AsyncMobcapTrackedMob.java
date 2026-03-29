package com.axalotl.async.common.spawn;

public interface AsyncMobcapTrackedMob {

    boolean async$countsTowardSpawnCap();

    void async$setCountsTowardSpawnCap(boolean countsTowardSpawnCap);

    default boolean async$isMarkedForSpawnCapDebug() {
        return this.async$countsTowardSpawnCap();
    }
}
