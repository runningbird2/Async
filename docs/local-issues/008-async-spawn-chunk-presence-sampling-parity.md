# Async Spawn Chunk Presence Sampling Parity

## Status

Open

## Summary

The async prepared spawn-state builder still samples chunk presence using off-thread `getChunkNow()` rather than a dedicated ready-full-chunk table or the exact same chunk-admission contract used by Leaf.

## Current Behavior

- async builder looks up each counted entity's chunk with `level.getChunkSource().getChunkNow(...)`
- entities in unresolved or changing chunk states are skipped from the prepared state

## Leaf Difference

Leaf reads from its ready full-chunk table directly when building `chunkCap`, which gives a slightly different chunk-availability view.

## Why This Matters

- chunk-edge or load/unload transitions can change which existing mobs contribute to the prepared state
- portal/lazy-chunk edge behavior may still differ from Leaf
- this can create small count drift in busy boundary cases

## Deferred Because

This is a lower-priority parity issue and not a proven blocker for normal farm behavior.

## Re-evaluate When

- portal-edge or lazy-chunk farms still behave oddly
- survival logs or live tests point back to chunk-admission drift
- we want stricter Leaf-style parity for async spawn counting
