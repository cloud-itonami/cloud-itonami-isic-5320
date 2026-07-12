(ns courierops.registry
  "Pure-function delivery-dispatch + delivery-settlement record
  construction -- an append-only courier book-of-record draft.

  This vertical is built on top of THREE real, pre-existing bespoke
  domain capability libraries rather than self-contained domain logic:

    - `kotoba-lang/logistics` -- `kotoba.logistics/tracking-valid?` is
      called directly for the parcel tracking number, not reimplemented
      (the same integration `cloud-itonami-isic-4920`'s freightops
      registry established).
    - `kotoba-lang/omise`     -- `kotoba.omise/pickup-available?` is the
      store-side pickup-window ground truth (store :active +
      :pickup-ready? + open at the pickup moment).
    - `kotoba-lang/okaimono`  -- `kotoba.okaimono/dispatchable?` is the
      order-side ground truth (only a :packed order may dispatch).

  Like every sibling actor's registry, there is no single international
  reference-number standard for a delivery-dispatch or delivery-
  settlement record -- every courier/jurisdiction assigns its own
  reference format. This namespace does NOT invent one; it builds a
  jurisdiction-scoped sequence number and validates the record's
  required fields, the same honest, non-fabricating discipline
  `courierops.facts` uses.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real dispatch/last-mile system. It builds the RECORD a
  courier operator would keep, not the act of dispatching or settling a
  delivery itself (that is `courierops.operation`'s `:delivery/
  dispatch`/`:delivery/settle`, always human-gated -- see README
  `Actuation`)."
  (:require [clojure.string :as str]
            [kotoba.logistics :as logistics]
            [kotoba.omise :as omise]
            [kotoba.okaimono :as okaimono]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the courier operator's act, not this actor's. See README `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

;; ------------------- capability-library delegations -------------------

(defn tracking-valid?
  "Delegates to `kotoba.logistics/tracking-valid?` -- the actor layer
  never reimplements the tracking-number structural contract."
  [tracking]
  (logistics/tracking-valid? tracking))

(defn pickup-available?
  "Delegates to `kotoba.omise/pickup-available?` -- the store must be
  :active, :pickup-ready? and open at the pickup moment. The actor
  layer never reimplements the opening-hours arithmetic."
  [store day hhmm]
  (omise/pickup-available? store day hhmm))

(defn order-dispatchable?
  "Delegates to `kotoba.okaimono/dispatchable?` -- only a :packed order
  may dispatch. The actor layer never reimplements the order lifecycle."
  [order]
  (okaimono/dispatchable? order))

;; ------------------- record drafts -------------------

(defn register-delivery-dispatch
  "Validate + construct the DELIVERY-DISPATCH registration DRAFT -- the
  courier operator's own act of dispatching a courier to pick a real
  parcel up from a store. Pure function -- does not touch any real
  dispatch system; it builds the RECORD an operator would keep.
  `courierops.governor` independently re-verifies the delivery's own
  tracking-number, store-pickup-window and order-lifecycle ground truth,
  and blocks a double-dispatch of the same delivery, before this is ever
  allowed to commit."
  [delivery-id jurisdiction sequence]
  (when-not (and delivery-id (not= delivery-id ""))
    (throw (ex-info "delivery-dispatch: delivery_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "delivery-dispatch: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "delivery-dispatch: sequence must be >= 0" {})))
  (let [dispatch-number (str (str/upper-case jurisdiction) "-DSP-" (zero-pad sequence 6))
        record {"record_id" dispatch-number
                "kind" "delivery-dispatch-draft"
                "delivery_id" delivery-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "dispatch_number" dispatch-number
     "certificate" (unsigned-certificate "DeliveryDispatch" dispatch-number dispatch-number)}))

(defn register-delivery-settlement
  "Validate + construct the DELIVERY-SETTLEMENT registration DRAFT --
  the courier operator's own act of settling a real delivery (fee /
  cash-on-delivery). Pure function -- does not touch any real payment
  system; it builds the RECORD an operator would keep.
  `courierops.governor` independently re-verifies the delivery's own
  proof-of-delivery and unresolved-exception ground truth, and blocks a
  double-settlement of the same delivery, before this is ever allowed
  to commit."
  [delivery-id jurisdiction sequence]
  (when-not (and delivery-id (not= delivery-id ""))
    (throw (ex-info "delivery-settlement: delivery_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "delivery-settlement: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "delivery-settlement: sequence must be >= 0" {})))
  (let [settlement-number (str (str/upper-case jurisdiction) "-STL-" (zero-pad sequence 6))
        record {"record_id" settlement-number
                "kind" "delivery-settlement-draft"
                "delivery_id" delivery-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "settlement_number" settlement-number
     "certificate" (unsigned-certificate "DeliverySettlement" settlement-number settlement-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
