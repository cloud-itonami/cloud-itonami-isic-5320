# Business model — Community Last-Mile Courier (ISIC Rev.5 5320)

## What the business is

A local courier operator serves a network of neighborhood merchants
(bookshops, grocers, cafés — the 商店街): a customer places an order
(お買い物) at a store (お店), the store packs it, a courier picks it up
within the store's opening hours and delivers it the same day, and the
delivery fee (and any cash-on-delivery amount) settles after a
proof-of-delivery is on file.

This is the workflow Shippify-class delivery-orchestration SaaS sells
back to merchants as a closed platform. This blueprint publishes the
same orchestration as a forkable OSS business, so the store, order and
proof-of-delivery data stays with the operator and the merchants.

## Revenue

- per-delivery fee (distance-banded via `kotoba.omise/distance-km`)
- merchant subscription for the operator console + exports
- COD handling fee (settlement is governor-gated)

## The `:courier-governor` Decision Rule

Approves dispatch of a courier for a delivery only when ALL of the
following hold:

1. the jurisdiction has an OFFICIAL spec-basis on file
   (`courierops.facts` — never a fabricated citation);
2. the jurisdiction's evidence checklist is fully satisfied
   (delivery-registration / operator-notification / tracking /
   parcel-liability-disclosure records);
3. the parcel's tracking number passes
   `kotoba.logistics/tracking-valid?`;
4. the origin store is `:active`, `:pickup-ready?` and OPEN at the
   pickup moment (`kotoba.omise/pickup-available?`);
5. the order has reached `:packed`
   (`kotoba.okaimono/dispatchable?`);
6. no unresolved delivery exception sits open;
7. the same delivery has not already been dispatched.

Approves settlement of a delivery only when a proof-of-delivery is
confirmed on file, no unresolved exception sits open, and the same
delivery has not already been settled.

Settlement-affecting and dispatch-affecting actions ALWAYS require a
human courier dispatcher's approval — no phase auto-commits them, and
delivery exceptions cannot be silently suppressed to force a dispatch
or settlement through.

## Data sovereignty

Every commit/hold decision lands on an append-only audit ledger; store
and order directories are capability-library records
(`kotoba.omise` / `kotoba.okaimono`) held as-is. A merchant can always
ask: which deliveries were dispatched for my store, on what basis,
approved by whom — and get an answer from an immutable log.
