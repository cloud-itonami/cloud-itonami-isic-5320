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

| Package | Customer | Price shape |
|---|---|---|
| Self-host starter | courier dispatcher / operator | setup fee + optional support retainer |
| Managed Starter | one local courier operator, 10–30 drivers, ~50 merchants | ¥45,000/月 flat |

**Market-anchored (2026-08-10)**: benchmarked against 7 real products at an
assumed size of one local courier operator with ~20 drivers/vehicles, ~50
merchant stores and 2,000–5,000 deliveries per month. **7 of 7 publish real
numbers** (only their Enterprise tiers are contact-sales) — this is the one
transparent market of the three verticals priced in this round, and both the
per-driver and the per-task/per-stop billing models are openly listed.

- **Onfleet** — LAUNCH $619/mo (2,500 delivery or pickup tasks), SCALE
  $1,349/mo (5,000 tasks), ENTERPRISE from $3,099/mo (10,000+); Courier Suite
  add-on from $299/mo; SMS/voice billed separately —
  <https://onfleet.com/pricing>. At ~¥150/$: **¥92,850–202,350/月**.
- **Track-POD** — per-driver Advanced $59/mo ($49 annual), Advanced Plus
  $79/$69, Ultimate $99/$89, minimum 3 drivers, Enterprise contact (min 20);
  per-order S $285/1,500, M $510/3,000, L $900/6,000, XL $1,440/12,000 —
  <https://www.track-pod.com/pricing-delivery-app/>. **¥76,500/月** (per-order
  M) to **¥177,000/月** (Advanced × 20 drivers).
- **OptimoRoute** — Lite $35.10 per driver/month, Pro $44.10 per driver/month,
  Custom contact-sales — <https://optimoroute.com/pricing/>. 20 drivers on
  Lite: **¥105,300/月**.
- **Detrack** — Pro "$29 per driver app billed monthly in USD", Advanced $39,
  Enterprise custom; unlimited jobs and office users —
  <https://www.detrack.com/pricing/>. 20 drivers on Pro: **¥87,000/月**.
- **Spoke Dispatch** (formerly Circuit for Teams; getcircuit.com 301-redirects
  here) — Starter $125/month (1,000 stops, $0.04 extra), Premium $200/month
  (2,000 stops, $0.06), Expert $1,000/month (12,000 stops, $0.07), unlimited
  drivers — <https://spoke.com/dispatch/pricing>. At 2,000–3,000 stops:
  **¥30,000–54,000/月**.
- **ODIN リアルタイム配送システム** (オンラインコンサルタント, 国内) — ODIN動態管理
  初期費用無料 + 月額1,500円(税込)/1ドライバー; ODIN配送計画 初期19万円(税込) +
  月額2,400円(税込)/1ドライバー または 59,000円(税込)〜/1拠点; ODIN PREMIUM
  初期19万円 + 月額2,800円(税込)/1ドライバー — <https://delivery-system.com/fee/>.
  20 drivers: **¥30,000/月** (動態管理) to **¥56,000/月** (PREMIUM), or
  **¥59,000/月** on the per-site plan.
- **ジオルーター** (配送・配車計画, 国内) — 月額利用プラン 初期費用50,000円 +
  「月30,000円/ユーザー」「金額は配送車５台以下の場合」, 30台以下は初期費用10万円で
  月額9万円; オンプレ フルパッケージ版 2,400,000円, ライト版 600,000円 (price list
  published on the ITmedia ITセレクト product page) —
  <https://www.itmedia.co.jp/itselect/product/300/>. **¥90,000/月** at ≤30
  vehicles.

The measured band at the assumed size is **¥30,000–202,350/月**, with the core
cluster at ¥56,000–105,000/月.

**¥45,000/月 sits in the lower third, and the upper half of the band is
rejected on purpose.** Everything above roughly ¥90,000/月 — Onfleet,
OptimoRoute, Track-POD's per-driver tiers, ジオルーター — is priced on route
optimisation and live driver telemetry. This actor has neither: route
optimisation is explicitly a follow-up slice and not in R0 (see Scope above),
and it performs no physical movement and no GPS tracking. **Importing an
optimisation product's price would be charging for a function that is not
shipped.** The comparable layer is dispatch/status management without
optimisation — ODIN 動態管理 at ¥30,000/月 for 20 drivers, Spoke Premium at
¥30,000–54,000/月 — and against that layer this actor adds what none of the
seven offer: a Governor that refuses dispatch when the origin store is shut,
the order is not `:packed`, the tracking number fails
`kotoba.logistics/tracking-valid?`, or an exception is open, and refuses
settlement without a confirmed POD — as HOLDs no human dispatcher can
override — plus a per-merchant audit ledger and data sovereignty. So
¥45,000/月 sits clearly above bare telemetry (¥30,000) and just below ODIN
PREMIUM / 配送計画 per-site (¥56,000–59,000).

It is **flat, not per-driver**, unlike most of the band: the load this actor
carries is the number of governed approval decisions, not the number of
drivers, so per-driver billing would scale the price against something that
does not scale the cost.

No portfolio-uniform ¥50,000–150,000/月 figure was inherited into this file,
and none was introduced: that range was anchored on HR/recruiting/CRM per-seat
SaaS and has no evidenced relationship to last-mile courier pricing — which,
uniquely here, did not need a proxy at all, since every comparator published a
real number.

**Subscribe (2026-08-10)**: a live Stripe Payment Link for the Managed Starter
tier (¥45,000/月 flat) is available now —
[**subscribe to Managed Starter**](https://buy.stripe.com/aFa9AU0E7dNK1n25PQeEo0i).
This is a no-code Stripe-hosted checkout; nothing in this repo's actor code
changed. **No courier operator has claimed or subscribed to this tier yet —
this is a live, working checkout with zero paid tenants, not a claim of
existing revenue.**

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
