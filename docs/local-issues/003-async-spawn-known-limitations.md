# Async Spawn Known Limitations

## Status

Open

## Accepted/Deferred Items

### One-tick prepared-state lag

The prepared spawn-state path is intentionally one tick behind vanilla. This is currently considered acceptable unless live testing shows visible gameplay issues.

### Local ownership drift during fast movement

Prepared local mobcap ownership is closer to vanilla than before, but fast movement/teleport cases may still create short-lived mismatches. This is currently considered lower priority than executor hardening and the lazy/portal despawn fix.

### Chunk-inclusion semantics

The prepared builder still does not exactly mirror vanilla chunk-admission semantics. This is worth revisiting only if live testing shows a real farm or portal-edge issue after the higher-priority fixes are ported.

## Re-evaluate When

- live testing still shows player-visible spawn inconsistencies
- portal-based farms still behave oddly after porting the despawn fix
- upstream review demands stricter vanilla equivalence
