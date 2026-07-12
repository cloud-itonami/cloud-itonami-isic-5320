# Contributing

`cloud-itonami-5320` accepts contributions to the OSS blueprint, capability
bindings, policy tests, documentation and operator model.

## Development
The capability layers live in `kotoba-lang/logistics` (tracking),
`kotoba-lang/omise` (stores/pickup windows) and `kotoba-lang/okaimono`
(orders/lifecycle). This repo holds the business blueprint, the governed
actor and operator contracts.

```bash
clojure -M:dev:test
clojure -M:lint
```

## Rules
- Do not commit real merchant, customer, route or delivery data.
- Keep dispatch, exceptions and settlements behind the Courier Governor.
- Treat courier workflows as high-risk: add tests for tracking, pickup
  windows, order lifecycle, POD, disclosure and audit logging.
- Never reimplement a capability library's validated logic in the actor
  layer -- delegate through `courierops.registry`.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests
PRs should describe: what behavior changed, which policy invariant is
affected, how it was tested, whether operator or certification docs need
updates.
