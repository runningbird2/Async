package com.axalotl.async.common.spawn;

/**
 * Controls how the planner turns a captured chunk snapshot into commit order.
 */
public enum AsyncSpawnChunkOrder {
    /**
     * Keep the chunk order captured on the main thread.
     */
    PRESERVE_SNAPSHOT,

    /**
     * Reorder the captured chunks off-thread from the snapshot seed without touching live world state.
     */
    DETERMINISTIC_SHUFFLE
}
