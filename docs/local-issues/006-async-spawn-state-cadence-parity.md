# Async Spawn State Cadence Parity

## Status

Open

## Summary

The current prepared spawn-state path only consumes async state if it lands on the exact next tick. If the task is late, the state is dropped and the server falls back to synchronous `createState(...)` for that tick.

## Current Behavior

- `ServerChunkCacheMixin` schedules a prepared state for `currentTick + 1`
- if the prepared task is not ready on that exact tick, it is not used
- a stale prepared task is cancelled and replaced

## Leaf Difference

Leaf keeps a background `lastSpawnState` and uses it whenever async counts are ready instead of requiring exact next-tick cadence.

## Why This Matters

- more sync fallbacks than Leaf under load
- less stable async benefit when the server is busy
- different freshness/performance tradeoff than Leaf

## Deferred Because

This is a parity/performance issue, not a known crash or correctness blocker for the current rollout.

## Re-evaluate When

- survival shows frequent sync `createState(...)` fallback under load
- async spawn gains are lower than expected
- we want closer behavioral parity with Leaf
