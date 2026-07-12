# Governance

`cloud-itonami-5320` is an OSS open-business blueprint for community
last-mile courier delivery.

## Maintainers
Maintainers may merge changes that preserve these invariants:
- deliveries with invalid tracking numbers can never dispatch.
- a courier is never dispatched to a store that is closed, suspended or
  not pickup-ready at the pickup moment (`kotoba.omise/pickup-available?`).
- a courier is never dispatched for an order that is not `:packed`
  (`kotoba.okaimono/dispatchable?`).
- a delivery is never settled without a confirmed proof-of-delivery.
- the Courier Governor remains independent of the advisor.
- hard policy violations (exception-suppression, force-settle) cannot be
  overridden by human approval.
- every dispatch, exception, settlement and POD path is auditable.
- merchant, customer, route and credentials data stays outside Git.

## Decision Records
Architecture decisions live in `docs/adr/`. Changes to the trust model,
storage contract, public business model, operator certification or
license should add or update an ADR.

## Operator Governance
Anyone may fork and operate independently. itonami.cloud certification
is a separate trust mark and should require security, audit and
data-flow review.

Certified operators can lose certification for:
- suppressing delivery exceptions to force a dispatch or settlement
- settling deliveries without a proof-of-delivery on file
- operating without the jurisdiction's required courier notification
- tampering with the audit ledger
