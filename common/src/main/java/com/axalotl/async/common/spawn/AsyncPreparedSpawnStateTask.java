package com.axalotl.async.common.spawn;

import java.util.concurrent.Future;

public record AsyncPreparedSpawnStateTask(long targetTick, Future<AsyncPreparedSpawnState> future) {
}
