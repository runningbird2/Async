# Async Spawn ChunkCap Apply Parity

## Status

Open

## Summary

The current chunkCap-style prepared state reapplies per-chunk mob counts through vanilla `LocalMobCapCalculator` and live `ChunkMap.getPlayersCloseForSpawning(...)` on the main thread.

## Current Behavior

- async build records per-chunk mob-category counts
- prepared state creates a fresh `LocalMobCapCalculator`
- chunk counts are replayed against live nearby-player lookups during consume

## Leaf Difference

Leaf applies `chunkCap` into Paper/Moonrise per-player mob-count arrays and related backoff state rather than rebuilding a vanilla `LocalMobCapCalculator`.

## Why This Matters

- player movement between build and consume can slightly change which players inherit last tick's mob pressure
- local-cap semantics are only approximate parity with Leaf/Paper
- this may widen the already accepted one-tick ownership drift behavior

## Deferred Because

This is not a known crash path and may be acceptable if live behavior remains stable.

## Re-evaluate When

- survival still shows player-local spawn stalls after the latest fixes
- movement/teleport-heavy cases still produce visible spawn inconsistencies
- we decide to chase closer Leaf/Paper parity
