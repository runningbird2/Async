# Async Entity Tick Navigation Deadlock

## Status

Open

## Summary

Survival hit a watchdog crash on 2026-03-22 where an async entity-tick worker held a synchronized `PathNavigation` monitor while waiting on chunk progress, and the server thread later blocked on that same navigation object during Lithium's active-navigation listener update path.

## Why This Matters

- This is a real watchdog/deadlock class, not just spawn-correctness drift.
- The crash is in async mob ticking, not the prepared spawn-state redesign.
- Portal or chunk-edge activity can make a small number of navigation-heavy mobs repeatedly hit this path.

## Current Mitigation

- Abort unsafe chunk waits from async entity-tick context.
- Fall the affected entity back to synchronous ticking for the current tick.
- Keep the entity synchronous for a short cooldown window to avoid thrashing.

## Re-evaluate When

- survival shows repeated async-abort fallback on the same entity classes or areas
- portal-edge or pathfinding-heavy activity still causes watchdog stalls
- we decide to pursue a deeper lock-free `PathNavigation` snapshot design
