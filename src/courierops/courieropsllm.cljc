(ns courierops.courieropsllm
  "CourierOps-LLM client -- the *contained intelligence node* for the
  community last-mile courier actor.

  It normalizes delivery intake, drafts a per-jurisdiction courier-
  operation/parcel-liability-disclosure evidence checklist, drafts the
  delivery-dispatch action, and drafts the delivery-settlement action.
  CRITICAL: it is a smart-but-untrusted advisor. It returns a
  *proposal* (with a rationale + the fields it cited), never a
  committed record or a real dispatch/settlement. Every output is
  censored downstream by `courierops.governor` before anything touches
  the SSoT, and `:delivery/dispatch`/`:delivery/settle` proposals
  NEVER auto-commit at any phase -- see README `Actuation`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :actuation/dispatch-delivery | :actuation/settle-delivery | nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [courierops.facts :as facts]
            [courierops.registry :as registry]
            [courierops.store :as store]
            [langchain.model :as model]))

(defn- normalize-intake
  "Directory upsert -- the LLM only normalizes/validates the patch; it
  does not invent the tracking number, store/order linkage or
  jurisdiction. High confidence, low stakes."
  [_db {:keys [patch]}]
  {:summary    (str "配送記録更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :delivery/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- assess-jurisdiction
  "Per-jurisdiction courier-operation/parcel-liability-disclosure
  evidence checklist draft. `:no-spec?` injects the failure mode we
  must defend against: proposing a checklist for a jurisdiction with NO
  official spec-basis in `courierops.facts` -- the Courier Governor
  must reject this (never invent a jurisdiction's requirements)."
  [db {:keys [subject no-spec?]}]
  (let [dl (store/delivery db subject)
        iso3 (if no-spec? "ATL" (:jurisdiction dl))
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str iso3 " の公式spec-basisが見つかりません")
       :rationale  "courierops.facts に未登録の法域。要件を推測で作らない。"
       :cites      []
       :effect     :assessment/set
       :value      {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str iso3 " (" (:owner-authority sb) ") 向け必要書類 "
                        (count (:required-evidence sb)) " 件を提案")
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :assessment/set
       :value      {:jurisdiction iso3
                    :checklist (:required-evidence sb)
                    :spec-basis (:provenance sb)
                    :legal-basis (:legal-basis sb)}
       :stake      nil
       :confidence 0.9})))

(defn- propose-delivery-dispatch
  "Draft the actual DELIVERY-DISPATCH action -- dispatching a real
  courier to pick a parcel up from a store. ALWAYS `:stake
  :actuation/dispatch-delivery` -- this is a REAL-WORLD act (a courier
  physically rides to the store), never a draft the actor may
  auto-run. See README `Actuation`: no phase ever adds this op to a
  phase's `:auto` set (`courierops.phase`); the governor also always
  escalates on `:actuation/dispatch-delivery`. Two independent layers
  agree, deliberately."
  [db {:keys [subject]}]
  (let [dl (store/delivery db subject)
        shop (when dl (store/store-of db (:store-id dl)))
        order (when dl (store/order-of db (:order-id dl)))
        tracking-ok? (and dl (registry/tracking-valid? (:tracking dl)))
        pickup-ok? (and shop (registry/pickup-available? shop (:pickup-day dl) (:pickup-time dl)))
        order-ok? (and order (registry/order-dispatchable? order))
        no-exception? (and dl (or (not (:exception-raised? dl)) (:exception-resolved? dl)))]
    {:summary    (str subject " 向け配車提案"
                      (when dl (str " (courier=" (:courier dl) ", store=" (:store-id dl) ")")))
     :rationale  (if dl
                   (str "tracking-valid?=" tracking-ok?
                        " pickup-available?=" pickup-ok?
                        " order-dispatchable?=" order-ok?
                        " exception-clear?=" no-exception?)
                   "deliveryが見つかりません")
     :cites      (if dl [subject] [])
     :effect     :delivery/mark-dispatched
     :value      {:delivery-id subject}
     :stake      :actuation/dispatch-delivery
     :confidence (if (and tracking-ok? pickup-ok? order-ok? no-exception?) 0.9 0.3)}))

(defn- propose-delivery-settlement
  "Draft the actual DELIVERY-SETTLEMENT action -- settling a real
  delivery (fee / cash-on-delivery). ALWAYS `:stake
  :actuation/settle-delivery` -- this is a REAL-WORLD act (real money
  moves between merchant, customer and courier), never a draft the
  actor may auto-run. See README `Actuation`: no phase ever adds this
  op to a phase's `:auto` set (`courierops.phase`); the governor also
  always escalates on `:actuation/settle-delivery`. Two independent
  layers agree, deliberately."
  [db {:keys [subject]}]
  (let [dl (store/delivery db subject)
        pod-ok? (and dl (:pod-confirmed? dl))
        no-exception? (and dl (or (not (:exception-raised? dl)) (:exception-resolved? dl)))]
    {:summary    (str subject " 向け精算提案"
                      (when dl (str " (cod=" (:cod-amount dl) ")")))
     :rationale  (if dl
                   (str "pod-confirmed?=" pod-ok?
                        " exception-clear?=" no-exception?)
                   "deliveryが見つかりません")
     :cites      (if dl [subject] [])
     :effect     :delivery/mark-settled
     :value      {:delivery-id subject}
     :stake      :actuation/settle-delivery
     :confidence (if (and pod-ok? no-exception?) 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :delivery/intake     (normalize-intake db request)
    :jurisdiction/assess (assess-jurisdiction db request)
    :delivery/dispatch   (propose-delivery-dispatch db request)
    :delivery/settle     (propose-delivery-settlement db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは地域ラストマイル配送事業者の配車・精算エージェントの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。"
       "説明や前置きは一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:delivery/upsert|:assessment/set|:delivery/mark-dispatched|"
       ":delivery/mark-settled) "
       ":stake(:actuation/dispatch-delivery か :actuation/settle-delivery か nil) :confidence(0..1)。\n"
       "重要: 登録されていない法域の要件を絶対に創作してはいけません。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。"
       "店舗の営業状況、注文のライフサイクル状態、配達証明(POD)の確認状況を"
       "偽って報告してはいけません。"))

(defn- facts-for [st {:keys [subject]}]
  (let [dl (store/delivery st subject)]
    {:delivery dl
     :store (when dl (store/store-of st (:store-id dl)))
     :order (when dl (store/order-of st (:order-id dl)))}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Courier Governor escalates/
  holds -- an LLM hiccup can never auto-dispatch or auto-settle a
  delivery."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :courieropsllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
