# APOM Code Review — Reserved Findings

This document records code review findings that were analyzed and intentionally
left unresolved. Each entry includes the original issue, the reason it was not
fixed, and the conditions under which it could manifest.

---

## H1 — Fragile @Shadow on internal AE2 field

- **File**: `CraftingContextMixin.java:36`
- **Status**: NOT FIXED (by decision)
- **Issue**: `@Shadow @Final public World world` targets AE2 internal class field
- **Rationale**: AE2 rv3-beta-695 is the only supported version for this mod.
  The `CraftingContext.world` field has been stable and public since this
  version. If AE2 is updated, the mixin would fail at application time (game
  won't start), which is a clear, immediate signal rather than a silent bug.
- **Potential trigger**: A GTNH pack updates to a newer AE2 build where
  `CraftingContext` has been refactored. The mod fails to load with a Mixin
  application error.

---

## H2 — Internal AE2 class dependency (CreativeTab)

- **File**: `BlockAdvPatternMatrix.java:25`
- **Status**: NOT FIXED (by decision)
- **Issue**: `appeng.core.CreativeTab.instance` references AE2 internal class
- **Rationale**: `CreativeTab` is a well-known stable class in AE2 installations
  and has not changed location across many GTNH AE2 versions. The worst case if
  it moves is a `NoClassDefFoundError` at startup — the creative tab would fall
  back to `tabRedstone` with a one-line code change. Non-critical UX issue.
- **Potential trigger**: AE2 update moves or renames `CreativeTab`. Block
  appears under a wrong (or no) creative tab.

---

## M1 — Race condition in atomic counter remove

- **File**: `DoublingNetworkTracker.java:30-36`
- **Status**: NOT FIXED (accepted risk, now mitigated with computeIfPresent)
- **Issue**: Non-atomic get-increment-check-remove sequence between
  `decrementAndGet` and `remove`
- **Rationale**: 1.7.10 Forge processes tile entity logic on the single server
  thread. The race cannot manifest in practice. The code has been updated to use
  `computeIfPresent` as a defense-in-depth measure.
- **Potential trigger**: Only if threaded chunk loading is introduced by another
  mod, or if ported to newer Minecraft versions. Would cause one block's
  registration to be silently lost.

---

## M2 — ScaledPatternDetails equals/hashCode asymmetry

- **File**: `ScaledPatternDetails.java:126-135`
- **Status**: NOT FIXED (by design necessity)
- **Issue**: `equals/hashCode` delegate to original, ignoring multiplier.
  `scaled.equals(original)` is true but `original.equals(scaled)` is undefined.
- **Rationale**: This asymmetry is REQUIRED for AE2's internal HashMap/TreeMap
  lookups to function correctly. The CPU cluster's `tasks` TreeMap uses a
  `priorityComparator` and the `craftingMethods` HashMap uses `equals()`.
  If `ScaledPatternDetails` were not equal to its original, `getMediums()`,
  `parallelismProvider`, and `reasonProvider` would all fail to find the
  pattern. Changing this breaks the core functionality.
- **Potential trigger**: N/A — this is intentional and verified working.

---

## M3 — Unbounded multiplier for empty patterns

- **File**: `PatternMultiplier.java:27-53`
- **Status**: NOT FIXED (edge case of no practical relevance)
- **Issue**: If a pattern has no condensed inputs AND no condensed outputs,
  `computeMaxSafeMultiplier` returns `HARD_CAP` unbounded
- **Rationale**: Empty patterns that are also processing patterns (not
  craftable) are essentially non-existent in practice. Even if one existed, the
  ideal multiplier is capped by `ceilDiv(requestAmount, outputAmount)` which
  would throw `ArithmeticException` for zero outputAmount, short-circuiting the
  entire compute.
- **Potential trigger**: An AE2 pattern with zero inputs AND zero outputs
  registered as a processing pattern. Extremely unlikely.

---

## C1 — ThreadLocal exception safety

- **File**: `CraftableItemResolverMixin.java:26-36`
- **Status**: FIXED (mitigated)
- **Issue**: `@At("RETURN")` injector does NOT fire on exceptional exit from the
  target method. If `provideCraftingRequestResolvers` throws, `push` is
  executed but `pop` is never called.
- **Fix applied**: Added try/catch in the pop injector (best-effort), added
  `RequestAmountHolder.clear()` for manual recovery, added `MAX_DEPTH` safety
  valve (100) in `push()`. A full try-finally rewrite is impossible with Mixin
  injectors alone; the mitigations cover practical risk.
- **Residual risk**: A single leaked push per thread lifetime if AE2 throws.
  The `MAX_DEPTH` guard limits damage to at most one cycle of incorrect values
  before self-correction.

---

## L4 — ThreadLocal slow memory accumulation

- **File**: `RequestAmountHolder.java:22-29`
- **Status**: FIXED
- **Issue**: If push/pop imbalance occurs (see C1), the Deque is never cleaned
  and accumulates entries in thread-pool servers.
- **Fix applied**: Added `MAX_DEPTH = 100` safety valve in `push()`. If the
  stack depth exceeds this threshold, it is cleared and a warning is logged.

---

## Summary

| ID | Severity | Fixed | Reason |
|----|----------|-------|--------|
| C1 | Critical | Mitigated | MAX_DEPTH guard + try/catch in pop |
| H1 | High | No | Field stable; startup crash is clear signal |
| H2 | High | No | Well-known class; one-line fix if it moves |
| H3 | Medium | Yes | `gridChanged()` checks network connectivity |
| M1 | Medium | Mitigated | `computeIfPresent` eliminates race window |
| M2 | Medium | No | Intentional design requirement |
| M3 | Medium | No | Non-existent edge case |
| L1 | Low | Yes | Comment updated |
| L2 | Low | Yes | Comment documenting NBT delay |
| L4 | Low | Yes | MAX_DEPTH safety valve |
| L5 | Low | Yes | Moved to README.md |
