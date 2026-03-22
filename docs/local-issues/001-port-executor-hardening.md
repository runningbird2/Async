# Port Executor Hardening From `f73f6e8`

## Status

Open

## Summary

The redesign branch still uses the older tick-pool rejection behavior. The prepared spawn-state path now relies on bounded task lifecycles, so the executor hardening from `f73f6e8` should be ported here as a follow-up.

## Why This Matters

- The prepared spawn-state scheduler assumes rejected work fails explicitly.
- The old executor setup can silently discard work instead of surfacing a rejection.
- That can leave a prepared-state slot stuck on a future that never completes.

## Intended Fix

- Port the tick-pool rejection/shutdown hardening from `f73f6e8`.
- Keep the port isolated in its own commit so it can be cherry-picked independently for upstream review.

## Notes

- This is separate from the spawn redesign itself.
- This should be done before relying on the prepared-state path in long-running live testing.
