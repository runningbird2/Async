# Async Portal Teleport Blocking Risk

## Status

Open

## Summary

The async entity-tick path can still block on portal teleport completion. `Entity.handlePortal()` routes Nether portal teleports through `PortalTeleportationManager.submitAndAwait(...)`, which waits for executor work that in turn waits for main-thread teleport execution.

## Why This Matters

- This is a potential stall/deadlock shape in async entity ticking.
- It is weaker than the reproduced `PathNavigation` deadlock because we have not shown the main thread blocking back on a lock held by the async worker.
- Portal-heavy farms or teleport-heavy movement are the most likely places for this to matter if it is real.

## Current Assessment

- Not currently reproduced as a survival crash.
- Not treated as a rollout blocker right now.
- Keep watching if survival shows stalls or watchdogs around portal activity.

## Re-evaluate When

- survival shows watchdog stalls during portal-heavy activity
- portal farms or repeated teleports line up with async tick stalls
- we investigate making portal handling non-blocking or sync-only from async tick context
