# Operator guide — cloud-itonami-5320

## Who operates this

A qualified courier dispatcher/operator serving a local merchant
network. The operator supplies the jurisdiction's courier-operation
notification (in Japan: 貨物軽自動車運送事業の届出), the real courier
fleet, and the payment integration. This software supplies the
governed, spec-cited, audited execution scaffold.

## Daily flow

1. **Intake** — new deliveries arrive as `:delivery/intake` (auto-commits
   when governor-clean at phase 3; every intake still lands on the
   ledger).
2. **Assess** — `:jurisdiction/assess` drafts the evidence checklist
   from `courierops.facts`; a human approves. A jurisdiction without an
   official spec-basis HOLDs — extend the catalog, never fabricate.
3. **Dispatch** — `:delivery/dispatch` ALWAYS pauses for your approval,
   and the governor HOLDs it un-overridably when the tracking number is
   malformed, the origin store is closed/suspended/not pickup-ready at
   the pickup moment, the order is not `:packed`, an exception is open,
   or the delivery was already dispatched.
4. **Settle** — `:delivery/settle` ALWAYS pauses for your approval, and
   HOLDs un-overridably without a confirmed proof-of-delivery.

## Rollout phases

Start at phase 0 (read-only) and advance 1 → 2 → 3 as trust builds.
Dispatch and settlement never auto-commit at any phase — that is a
structural fact of `courierops.phase`, not a milestone.

## Extending the jurisdiction catalog

Add entries to `courierops.facts/catalog` with an official
`:owner-authority` / `:legal-basis` / `:provenance` AND a separate
parcel-liability-disclosure citation. Coverage is reported honestly —
a missing jurisdiction HOLDs rather than guessing.

## Store and order directories

Stores are `kotoba.omise` records; orders are `kotoba.okaimono`
records — capability-library data held as-is in the actor's store
(`courierops.store`). Operator consoles and exports for both live in
the capability libraries (`kotoba.omise.ui` / `kotoba.okaimono.ui` /
`.export`), read-only by construction.
