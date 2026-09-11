(ns courierops.governor
  "Courier Governor -- the independent compliance layer that earns the
  CourierOps-LLM the right to commit. The LLM has no notion of
  jurisdictional courier-operation/parcel-liability-disclosure law,
  whether a delivery's own tracking number is structurally valid,
  whether the origin store is actually open (and pickup-ready, and
  :active) at the proposed pickup moment, whether the underlying
  shopping order has actually reached a dispatchable lifecycle state,
  whether a proof-of-delivery is actually on file before a settlement,
  whether an open delivery exception has actually been resolved, or
  when an act stops being a draft and becomes a real-world courier
  dispatch or delivery settlement, so this MUST be a separate system
  able to *reject* a proposal and fall back to HOLD.

  This vertical is built on TOP of THREE real, pre-existing bespoke
  domain capability libraries rather than self-contained domain logic
  (`kotoba-lang/logistics`, `kotoba-lang/omise`, `kotoba-lang/
  okaimono`) -- `courierops.registry` calls `kotoba.logistics/
  tracking-valid?`, `kotoba.omise/pickup-available?` and
  `kotoba.okaimono/dispatchable?` directly rather than reimplementing
  them, extending the capability-library-reuse discipline
  `retailops`/4711 and `freightops`/4920 established from one library
  to three. This blueprint's own `docs/business-model.md` publishes the
  `:courier-governor` Decision Rule naming exactly the checks below;
  this governor implements that published design.

  `:itonami.blueprint/governor` is `:courier-governor` -- a fresh
  independent build following the SAME governed-actor architecture
  (langgraph StateGraph + independent Governor + Phase 0->3 rollout)
  as every prior actor in this fleet.

  Seven checks, in priority order, ALL HARD violations: a human
  approver CANNOT override them. The confidence/actuation gate is
  SOFT: it asks a human to look (low confidence / actuation), and the
  human may approve -- but see `courierops.phase`: for `:stake
  :actuation/dispatch-delivery`/`:actuation/settle-delivery` (a real
  courier dispatch or delivery settlement) NO phase ever allows
  auto-commit either. Two independent layers agree that actuation is
  always a human call.

    1. Spec-basis                  -- did the jurisdiction proposal cite
                                      an OFFICIAL source
                                      (`courierops.facts`), or invent
                                      one?
    2. Evidence incomplete         -- for `:delivery/dispatch`/
                                      `:delivery/settle`, has the
                                      jurisdiction actually been
                                      assessed with a full evidence
                                      checklist on file?
    3. Tracking number invalid     -- for `:delivery/dispatch`,
                                      INDEPENDENTLY verify the
                                      delivery's own tracking number
                                      via `kotoba.logistics/tracking-
                                      valid?` (through `courierops.
                                      registry`). Evaluated
                                      UNCONDITIONALLY (every dispatch
                                      needs a valid tracking number).
    4. Store pickup unavailable    -- for `:delivery/dispatch`,
                                      INDEPENDENTLY verify the origin
                                      store is :active, :pickup-ready?
                                      and OPEN at the delivery's own
                                      pickup day/time via `kotoba.
                                      omise/pickup-available?` (through
                                      `courierops.registry`) -- the
                                      FIRST store-side pickup-window
                                      check in this fleet, grounded in
                                      the plain operational fact that
                                      dispatching a courier to a
                                      closed/suspended store burns real
                                      courier time and strands the
                                      parcel. Evaluated UNCONDITIONALLY
                                      (every dispatch needs an open,
                                      pickup-ready origin store).
    5. Order not dispatchable      -- for `:delivery/dispatch`,
                                      INDEPENDENTLY verify the
                                      underlying shopping order has
                                      reached the :packed lifecycle
                                      state via `kotoba.okaimono/
                                      dispatchable?` (through
                                      `courierops.registry`) -- never
                                      dispatch a courier for an order
                                      the store has not finished
                                      packing, or has cancelled.
                                      Evaluated UNCONDITIONALLY.
    6. POD unconfirmed / exception -- for `:delivery/settle`,
                                      INDEPENDENTLY verify the
                                      delivery's own `:pod-confirmed?`
                                      is true (no settlement without a
                                      proof-of-delivery on file --
                                      grounded in the same parcel-
                                      liability regimes `courierops.
                                      facts` cites: 標準宅配便運送約款
                                      第25条, Carmack 49 U.S.C. §14706,
                                      Consumer Rights Act 2015, HGB
                                      §407 ff.); and for BOTH
                                      `:delivery/dispatch` and
                                      `:delivery/settle`, an unresolved
                                      delivery exception is a HARD,
                                      un-overridable hold.
    7. Confidence floor / actuation
       gate                        -- LLM confidence below threshold,
                                      OR the op is `:delivery/dispatch`
                                      /`:delivery/settle` (REAL acts)
                                      -> escalate.

  Two more guards, double-dispatch/double-settlement prevention, are
  enforced off dedicated `:dispatched?`/`:settled?` facts (never a
  `:status` value) -- the SAME 'check a dedicated boolean, not status'
  discipline every prior governor's guards establish."
  (:require [courierops.facts :as facts]
            [courierops.registry :as registry]
            [courierops.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Dispatching a real courier and settling a real delivery are the two
  real-world actuation events this actor performs."
  #{:actuation/dispatch-delivery :actuation/settle-delivery})

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A `:jurisdiction/assess` (or `:delivery/dispatch`/`:delivery/settle`)
  proposal with no spec-basis citation is a HARD violation -- never
  invent a jurisdiction's courier-operation/parcel-liability-disclosure
  requirements."
  [{:keys [op]} proposal]
  (when (contains? #{:jurisdiction/assess :delivery/dispatch :delivery/settle} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は法域要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For `:delivery/dispatch`/`:delivery/settle`, the jurisdiction's
  required delivery-registration/operator-notification/tracking/
  parcel-liability evidence must actually be satisfied -- do not trust
  the advisor's self-reported confidence alone."
  [{:keys [op subject]} st]
  (when (contains? #{:delivery/dispatch :delivery/settle} op)
    (let [dl (store/delivery st subject)
          assessment (store/assessment-of st subject)]
      (when-not (and assessment
                     (facts/required-evidence-satisfied?
                      (:jurisdiction dl) (:checklist assessment)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(配送登録記録/事業者届出記録/追跡記録/宅配便責任限度額開示記録等)が充足していない状態での提案"}]))))

(defn- tracking-number-invalid-violations
  "For `:delivery/dispatch`, INDEPENDENTLY verify the delivery's own
  tracking number passes `kotoba.logistics/tracking-valid?` (through
  `courierops.registry/tracking-valid?`). Evaluated UNCONDITIONALLY
  (every dispatch needs a valid tracking number)."
  [{:keys [op subject]} st]
  (when (= op :delivery/dispatch)
    (let [dl (store/delivery st subject)]
      (when-not (registry/tracking-valid? (:tracking dl))
        [{:rule :tracking-number-invalid
          :detail (str subject " の追跡番号(" (:tracking dl) ")が構造検証に失敗")}]))))

(defn- store-pickup-unavailable-violations
  "For `:delivery/dispatch`, INDEPENDENTLY verify the origin store is
  :active, :pickup-ready? and OPEN at the delivery's own pickup
  day/time via `kotoba.omise/pickup-available?` (through
  `courierops.registry/pickup-available?`) -- never dispatch a courier
  to a closed or suspended store. Evaluated UNCONDITIONALLY."
  [{:keys [op subject]} st]
  (when (= op :delivery/dispatch)
    (let [dl (store/delivery st subject)
          shop (store/store-of st (:store-id dl))]
      (when-not (and shop (registry/pickup-available? shop (:pickup-day dl) (:pickup-time dl)))
        [{:rule :store-pickup-unavailable
          :detail (str subject " の集荷元店舗(" (:store-id dl) ")が集荷時刻("
                       (some-> (:pickup-day dl) name) " " (:pickup-time dl)
                       ")に営業中・集荷可能でない -- 配車提案は進められない")}]))))

(defn- order-not-dispatchable-violations
  "For `:delivery/dispatch`, INDEPENDENTLY verify the underlying
  shopping order has reached the :packed lifecycle state via
  `kotoba.okaimono/dispatchable?` (through `courierops.registry/
  order-dispatchable?`) -- never dispatch a courier for an unpacked or
  cancelled order. Evaluated UNCONDITIONALLY."
  [{:keys [op subject]} st]
  (when (= op :delivery/dispatch)
    (let [dl (store/delivery st subject)
          order (store/order-of st (:order-id dl))]
      (when-not (and order (registry/order-dispatchable? order))
        [{:rule :order-not-dispatchable
          :detail (str subject " の注文(" (:order-id dl)
                       ")が :packed 状態でない -- 配車提案は進められない")}]))))

(defn- pod-unconfirmed-violations
  "For `:delivery/settle`, INDEPENDENTLY verify the delivery's own
  `:pod-confirmed?` is true -- no settlement without a proof-of-
  delivery on file. Evaluated UNCONDITIONALLY (every settlement needs
  its POD)."
  [{:keys [op subject]} st]
  (when (= op :delivery/settle)
    (let [dl (store/delivery st subject)]
      (when-not (true? (:pod-confirmed? dl))
        [{:rule :pod-unconfirmed
          :detail (str subject " は配達証明(POD)が未確認 -- 精算提案は進められない")}]))))

(defn- delivery-exception-unresolved-violations
  "An unresolved delivery exception -- reported by THIS proposal itself,
  or already on file for the delivery -- is a HARD, un-overridable
  hold. Evaluated UNCONDITIONALLY across both `:delivery/dispatch` and
  `:delivery/settle` so neither op can proceed while an exception sits
  open."
  [{:keys [op subject]} st]
  (when (contains? #{:delivery/dispatch :delivery/settle} op)
    (let [dl (store/delivery st subject)]
      (when (and (true? (:exception-raised? dl)) (not (true? (:exception-resolved? dl))))
        [{:rule :delivery-exception-unresolved
          :detail (str subject " は未解決の配送例外がある -- 配車/精算提案は進められない")}]))))

(defn- already-dispatched-violations
  "For `:delivery/dispatch`, refuses to dispatch the SAME delivery
  twice, off a dedicated `:dispatched?` fact (never a `:status`
  value)."
  [{:keys [op subject]} st]
  (when (= op :delivery/dispatch)
    (when (store/delivery-already-dispatched? st subject)
      [{:rule :already-dispatched
        :detail (str subject " は既に配車済み")}])))

(defn- already-settled-violations
  "For `:delivery/settle`, refuses to settle the SAME delivery twice,
  off a dedicated `:settled?` fact (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :delivery/settle)
    (when (store/delivery-already-settled? st subject)
      [{:rule :already-settled
        :detail (str subject " は既に精算済み")}])))

(defn check
  "Censors a CourierOps-LLM proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (tracking-number-invalid-violations request st)
                           (store-pickup-unavailable-violations request st)
                           (order-not-dispatchable-violations request st)
                           (pod-unconfirmed-violations request st)
                           (delivery-exception-unresolved-violations request st)
                           (already-dispatched-violations request st)
                           (already-settled-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
