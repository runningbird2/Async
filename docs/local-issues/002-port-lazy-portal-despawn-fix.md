# Port Lazy/Portal Despawn Fix From `ffb3967`

## Status

Open

## Summary

The redesign branch still has the older async despawn path in `ServerLevelMixin`. The lazy/portal-chunk despawn fix from `ffb3967` should be ported on top of this branch before production validation.

## Why This Matters

- Portal-adjacent and lazy-loaded chunks are the highest-risk area for wrong despawn behavior.
- The spawn redesign does not address that entity/despawn bug.
- A branch can look good for spawning while still regressing lazy-chunk mob persistence.

## Intended Fix

- Port the `ffb3967` change that keeps despawn checks on the level thread.
- Keep it as a separate commit from the spawn redesign so it can be included or excluded cleanly in an upstream PR.

## Notes

- This patch is expected to layer cleanly on top of the spawn redesign because it touches the entity tick/despawn path, not the prepared spawn-state builder.
