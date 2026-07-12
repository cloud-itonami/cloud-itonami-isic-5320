# ADR 0001 — Architecture: CourierOps-LLM ⊣ Courier Governor

**Status**: accepted
**Date**: 2026-07-12

## Context

The workspace decided (superproject ADR-2607121900) to replace
Shippify-class last-mile delivery-orchestration SaaS with an open,
governed courier actor: ISIC Rev.5 5320 (courier activities) was the
one logistics-adjacent vertical with no blueprint in the fleet, while
its three domain ground truths already existed or were built alongside
as kotoba-lang capability libraries:

- `kotoba-lang/logistics` — tracking-number structural contract
  (pre-existing, also serves `cloud-itonami-isic-4920`)
- `kotoba-lang/omise` — store records, opening hours,
  `pickup-available?` (built with this actor)
- `kotoba-lang/okaimono` — order records, lifecycle,
  `dispatchable?` (built with this actor)

## Decision

Follow the SAME governed-actor architecture as every prior actor in
this fleet (langgraph StateGraph + independent Governor + Phase 0→3
rollout + append-only ledger + MemStore ≡ DatomicStore contract),
with:

1. **One entity, dual actuation, sequential** — `delivery` carries
   both real-world acts (`dispatch` first, `settle` later), with
   dedicated `:dispatched?`/`:settled?` guard booleans (never a
   `:status` value) — the freightops/4920 shape, not retailops/4711's
   `:kind`-distinguished shape.
2. **Three-capability-library integration** — the governor's new
   checks are DELEGATIONS, not reimplementations:
   `:tracking-number-invalid` → `kotoba.logistics/tracking-valid?`,
   `:store-pickup-unavailable` → `kotoba.omise/pickup-available?`
   (the fleet's first store-side pickup-window check),
   `:order-not-dispatchable` → `kotoba.okaimono/dispatchable?`
   (the fleet's first order-lifecycle check). All three route through
   `courierops.registry` so the actor layer has exactly one seam to
   the capability layer.
3. **Settlement requires POD** — `:pod-unconfirmed` HOLDs any
   `:delivery/settle` without a confirmed proof-of-delivery, grounded
   in the parcel-liability regimes `courierops.facts` cites
   (標準宅配便運送約款 第25条 / Carmack 49 U.S.C. §14706 / Consumer
   Rights Act 2015 / HGB §407 ff.).
4. **Honest jurisdiction catalog** — R0 seeds JPN/USA/GBR/DEU with
   courier-specific citations; GBR/DEU light-vehicle licensing
   exemptions are stated in the entries rather than papered over; a
   missing jurisdiction HOLDs.
5. **Actuation is never autonomous** — `:delivery/dispatch`/
   `:delivery/settle` are absent from every phase's `:auto` set AND
   always escalate via the governor's high-stakes gate. Two layers,
   independently.

## Consequences

- 40 tests / 200 assertions run offline
  (facts, registry, phase, governor contract, store contract on both
  backends); `clojure -M:dev:run` walks the clean path plus every
  HARD-hold scenario.
- The store/order directories hold `kotoba.omise`/`kotoba.okaimono`
  records as-is (EDN-encoded in the Datomic backend), so capability
  and actor layers never diverge on data shape.
- Route optimization and real fleet/payment integrations are follow-up
  slices; the R0 boundary is drafts + governed approval + ledger.
