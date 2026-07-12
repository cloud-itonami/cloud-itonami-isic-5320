# cloud-itonami-isic-5320

Open Business Blueprint for **ISIC Rev.5 5320**: Community Last-Mile
Courier -- delivery intake, per-jurisdiction courier-operation/
parcel-liability-disclosure regulatory assessment, courier dispatch
and delivery settlement for a local merchant network.

This repository publishes a community last-mile courier actor as an
OSS business that any qualified operator can fork, deploy, run,
improve and sell, so a local merchant network never surrenders store,
order and proof-of-delivery data to a closed delivery-orchestration
SaaS (the Shippify-class platform this blueprint replaces -- see
ADR-2607121900 in com-junkawasaki/root).

Built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as
every prior actor in this fleet -- here it is
**CourierOps-LLM ⊣ Courier Governor**. This blueprint's own
`:itonami.blueprint/governor` keyword is `:courier-governor`.

**This vertical is built on top of THREE real, pre-existing bespoke
domain capability libraries** rather than self-contained domain logic
(extending the one-library integration `retailops`/4711 and
`freightops`/4920 established):

- [`kotoba-lang/logistics`](https://github.com/kotoba-lang/logistics)
  -- `kotoba.logistics/tracking-valid?` is the parcel tracking-number
  structural contract.
- [`kotoba-lang/omise`](https://github.com/kotoba-lang/omise)
  -- `kotoba.omise/pickup-available?` is the store-side ground truth
  (店舗 :active + :pickup-ready? + open at the pickup moment).
- [`kotoba-lang/okaimono`](https://github.com/kotoba-lang/okaimono)
  -- `kotoba.okaimono/dispatchable?` is the order-side ground truth
  (only a :packed お買い物 order may dispatch).

`courierops.registry` calls all three directly; the actor layer adds
the governed proposal/approval loop on top, it never reimplements the
capability libraries' own validated logic.

> **Why an actor layer at all?** An LLM is great at drafting a
> delivery summary, normalizing records, and checking a tracking
> number's basic shape -- but it has **no notion of which
> jurisdiction's courier-operation/parcel-liability-disclosure law is
> official, no license to dispatch a real courier or settle a real
> delivery, and no way to know on its own whether the origin store is
> actually open at the pickup moment, whether the order is actually
> packed, or whether a proof-of-delivery is actually on file**.
> Letting it dispatch or settle directly invites fabricated regulatory
> citations, couriers riding to closed stores for unpacked orders, and
> settlements closing without a POD -- and liability, for whoever runs
> it. This project seals the CourierOps-LLM into a single node and
> wraps it with an independent **Courier Governor**, a human
> **approval workflow**, and an immutable **audit ledger**.

## Scope: what this actor does and does not do

This actor covers delivery intake through courier-operation/
parcel-liability-disclosure regulatory assessment, courier dispatch
and delivery settlement. It does **not**, by itself, hold any
operating notification/authority required to run a courier business in
a given jurisdiction, and it does not claim to. It also does not
perform the actual physical movement of parcels, or judge route
quality -- route optimization (the blueprint's `:optimization`
technology) is a follow-up slice, not in this R0. Whoever deploys and
operates a live instance (a qualified courier dispatcher/operator)
supplies any jurisdiction-specific operating notification, the real
courier-fleet integration and the real payment integrations, and bears
that jurisdiction's liability -- the software supplies the governed,
spec-cited, audited execution scaffold so that operator does not have
to build the compliance layer from scratch.

### Actuation

**Dispatching a real courier and settling a real delivery are never
autonomous, at any phase, by construction.** Two independent layers
enforce this (`courierops.governor`'s `:actuation/dispatch-delivery`/
`:actuation/settle-delivery` high-stakes gate and `courierops.phase`'s
phase table, which never puts either op in any phase's `:auto` set) --
see `courierops.phase`'s docstring and `test/courierops/phase_test.clj`'s
`delivery-dispatch-never-auto-at-any-phase`/
`delivery-settle-never-auto-at-any-phase`. The actor may draft, check
and recommend; a human courier dispatcher is always the one who
actually dispatches a courier or settles a delivery.

## The core contract

```
delivery intake + jurisdiction facts (courierops.facts, spec-cited)
        |
        v
   ┌───────────────────┐   proposal      ┌──────────────────────────────┐
   │ CourierOps-LLM    │ ─────────────▶ │ Courier Governor              │  (independent system)
   │ (sealed)          │  + citations    │ spec-basis · evidence ·       │
   └───────────────────┘                 │ tracking (logistics) ·        │
                                         │ pickup window (omise) ·       │
                                         │ order lifecycle (okaimono) ·  │
                                         │ POD · exception · duplicates  │
                                         └───────┬──────────────────────┘
                                                 │ commit / hold / escalate
                                                 ▼
                                   human approval (interrupt-before)
                                                 │
                                                 ▼
                                SSoT + append-only audit ledger
```

## Run

```sh
clojure -M:dev:test   # 40 tests / 200 assertions
clojure -M:lint       # clj-kondo, errors fail
clojure -M:dev:run    # demo: clean walk + every HARD-hold scenario
```

## Maturity

`:implemented` -- `CourierOps-LLM` + `Courier Governor` run as real,
tested code (see `Run` above), following the SAME governed-actor
architecture as the prior actors across this fleet, with its own
distinct, independently-named governor and the fleet's FIRST
three-capability-library integration (`kotoba-lang/logistics` +
`kotoba-lang/omise` + `kotoba-lang/okaimono`). See
`docs/adr/0001-architecture.md` for the history and design.

## License

AGPL-3.0-or-later.
