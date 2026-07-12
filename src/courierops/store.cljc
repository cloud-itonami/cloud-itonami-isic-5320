(ns courierops.store
  "SSoT for the community last-mile courier actor, behind a `Store`
  protocol so the backend is a swap, not a rewrite -- the same seam
  every prior `cloud-itonami-isic-*` actor in this fleet uses.

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/courierops/store_contract_test.clj).

  Alongside the delivery directory, this store also holds the STORE
  directory (`kotoba.omise` records) and the ORDER directory
  (`kotoba.okaimono` records) the Courier Governor reads for its
  pickup-window and order-lifecycle ground truth -- both are capability-
  library records held as-is, never reshaped. Like `freightops`/4920's
  `shipment`, the `dispatch` and `settle` actuation events apply
  SEQUENTIALLY to the SAME `delivery` (dispatch first, settlement
  later), with dedicated double-actuation-guard booleans
  (`:dispatched?`/`:settled?`, never a `:status` value).

  The ledger stays append-only on every backend: 'which delivery was
  screened for an invalid tracking number, a closed pickup window, an
  undispatched order, an unconfirmed proof-of-delivery or an unresolved
  delivery exception, which delivery was dispatched, which was settled,
  on what jurisdictional basis, approved by whom' is always a query
  over an immutable log -- the audit trail a merchant or customer
  trusting a courier operator needs."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [courierops.registry :as registry]
            [langchain.db :as d]
            [kotoba.okaimono :as okaimono]
            [kotoba.omise :as omise]))

(defprotocol Store
  (delivery [s id])
  (all-deliveries [s])
  (store-of [s store-id] "the kotoba.omise store record, or nil")
  (order-of [s order-id] "the kotoba.okaimono order record, or nil")
  (assessment-of [s delivery-id] "committed jurisdiction assessment, or nil")
  (ledger [s])
  (dispatch-history [s] "the append-only delivery-dispatch history (courierops.registry drafts)")
  (settlement-history [s] "the append-only delivery-settlement history (courierops.registry drafts)")
  (next-dispatch-sequence [s jurisdiction] "next dispatch-number sequence for a jurisdiction")
  (next-settlement-sequence [s jurisdiction] "next settlement-number sequence for a jurisdiction")
  (delivery-already-dispatched? [s delivery-id] "has this delivery already been dispatched?")
  (delivery-already-settled? [s delivery-id] "has this delivery already been settled?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-deliveries [s deliveries] "replace/seed the delivery directory (map id->delivery)"))

;; ----------------------------- demo data -----------------------------

(def ^:private demo-hours
  {:mon [["09:00" "18:00"]] :tue [["09:00" "18:00"]] :wed [["09:00" "18:00"]]
   :thu [["09:00" "18:00"]] :fri [["09:00" "18:00"]] :sat [["10:00" "17:00"]]})

(defn demo-data
  "A small, self-contained store/order/delivery set covering both
  actuation lifecycles (dispatch, settlement) plus the governor's own
  checks, so the actor + tests run offline. Stores are `kotoba.omise`
  records; orders are `kotoba.okaimono` records -- capability-library
  data held as-is."
  []
  (let [lines [(okaimono/line "sku-1" "Coffee beans 200g" 2 900)]
        packed-order (fn [id] (-> (okaimono/order id "st-kanda" {:name "Tanaka"} lines)
                                  (okaimono/advance :confirmed)
                                  (okaimono/advance :packed)))]
    {:stores
     {"st-kanda"  (omise/store "st-kanda" "Kanda Books" "1-1 Kanda, Tokyo"
                               :hours demo-hours :jurisdiction "JPN" :pickup-ready? true)
      "st-paused" (omise/store "st-paused" "Paused Shop" "9-9 Ueno, Tokyo"
                               :hours demo-hours :jurisdiction "JPN" :pickup-ready? true
                               :status :suspended)}
     :orders
     {"ok-1" (packed-order "ok-1")
      "ok-2" (okaimono/order "ok-2" "st-kanda" {:name "Sato"} lines)   ; :placed -- not dispatchable
      "ok-3" (packed-order "ok-3")
      "ok-4" (packed-order "ok-4")
      "ok-5" (packed-order "ok-5")
      "ok-6" (packed-order "ok-6")
      "ok-7" (packed-order "ok-7")}
     :deliveries
     {"delivery-1" {:id "delivery-1" :order-id "ok-1" :store-id "st-kanda"
                    :tracking "1Z999AA10123456784" :courier "courier-7"
                    :recipient "Tanaka" :destination "2-2 Yanaka, Tokyo"
                    :pickup-day :mon :pickup-time "10:00"
                    :pod-confirmed? true
                    :exception-raised? false :exception-resolved? false
                    :dispatched? false :settled? false
                    :cod-amount 1800 :jurisdiction "JPN" :status :intake}
      "delivery-2" {:id "delivery-2" :order-id "ok-3" :store-id "st-kanda"
                    :tracking "1Z999AA10123456784" :courier "courier-7"
                    :recipient "Umi" :destination "Atlantis City"
                    :pickup-day :mon :pickup-time "10:00"
                    :pod-confirmed? true
                    :exception-raised? false :exception-resolved? false
                    :dispatched? false :settled? false
                    :cod-amount 0 :jurisdiction "ATL" :status :intake}
      "delivery-3" {:id "delivery-3" :order-id "ok-3" :store-id "st-kanda"
                    :tracking "BAD#TRACK123" :courier "courier-7"
                    :recipient "Suzuki" :destination "3-3 Nezu, Tokyo"
                    :pickup-day :mon :pickup-time "10:00"
                    :pod-confirmed? true
                    :exception-raised? false :exception-resolved? false
                    :dispatched? false :settled? false
                    :cod-amount 0 :jurisdiction "JPN" :status :intake}
      "delivery-4" {:id "delivery-4" :order-id "ok-4" :store-id "st-kanda"
                    :tracking "1Z999AA10123456784" :courier "courier-7"
                    :recipient "Ito" :destination "4-4 Sendagi, Tokyo"
                    :pickup-day :sun :pickup-time "10:00"      ; store closed on :sun
                    :pod-confirmed? true
                    :exception-raised? false :exception-resolved? false
                    :dispatched? false :settled? false
                    :cod-amount 0 :jurisdiction "JPN" :status :intake}
      "delivery-5" {:id "delivery-5" :order-id "ok-2" :store-id "st-kanda"
                    :tracking "1Z999AA10123456784" :courier "courier-7"
                    :recipient "Sato" :destination "5-5 Hongo, Tokyo"
                    :pickup-day :mon :pickup-time "10:00"
                    :pod-confirmed? true
                    :exception-raised? false :exception-resolved? false
                    :dispatched? false :settled? false
                    :cod-amount 0 :jurisdiction "JPN" :status :intake}   ; ok-2 is :placed
      "delivery-6" {:id "delivery-6" :order-id "ok-5" :store-id "st-kanda"
                    :tracking "1Z999AA10123456784" :courier "courier-7"
                    :recipient "Kimura" :destination "6-6 Komagome, Tokyo"
                    :pickup-day :mon :pickup-time "10:00"
                    :pod-confirmed? false                       ; settle must hold
                    :exception-raised? false :exception-resolved? false
                    :dispatched? false :settled? false
                    :cod-amount 2400 :jurisdiction "JPN" :status :intake}
      "delivery-7" {:id "delivery-7" :order-id "ok-6" :store-id "st-kanda"
                    :tracking "1Z999AA10123456784" :courier "courier-7"
                    :recipient "Mori" :destination "7-7 Tabata, Tokyo"
                    :pickup-day :mon :pickup-time "10:00"
                    :pod-confirmed? true
                    :exception-raised? true :exception-resolved? false   ; open exception
                    :dispatched? false :settled? false
                    :cod-amount 0 :jurisdiction "JPN" :status :intake}}}))

;; ----------------------------- shared commit logic -----------------------------

(defn- dispatch-delivery!
  "Backend-agnostic `:delivery/mark-dispatched` -- looks up the delivery
  via the protocol and drafts the dispatch record, and returns
  {:result .. :delivery-patch ..} for the caller to persist."
  [s delivery-id]
  (let [dl (delivery s delivery-id)
        seq-n (next-dispatch-sequence s (:jurisdiction dl))
        result (registry/register-delivery-dispatch delivery-id (:jurisdiction dl) seq-n)]
    {:result result
     :delivery-patch {:dispatched? true
                      :dispatch-number (get result "dispatch_number")}}))

(defn- settle-delivery!
  "Backend-agnostic `:delivery/mark-settled` -- looks up the delivery
  via the protocol and drafts the settlement record, and returns
  {:result .. :delivery-patch ..} for the caller to persist."
  [s delivery-id]
  (let [dl (delivery s delivery-id)
        seq-n (next-settlement-sequence s (:jurisdiction dl))
        result (registry/register-delivery-settlement delivery-id (:jurisdiction dl) seq-n)]
    {:result result
     :delivery-patch {:settled? true
                      :settlement-number (get result "settlement_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (delivery [_ id] (get-in @a [:deliveries id]))
  (all-deliveries [_] (sort-by :id (vals (:deliveries @a))))
  (store-of [_ store-id] (get-in @a [:stores store-id]))
  (order-of [_ order-id] (get-in @a [:orders order-id]))
  (assessment-of [_ delivery-id] (get-in @a [:assessments delivery-id]))
  (ledger [_] (:ledger @a))
  (dispatch-history [_] (:dispatches @a))
  (settlement-history [_] (:settlements @a))
  (next-dispatch-sequence [_ jurisdiction] (get-in @a [:dispatch-sequences jurisdiction] 0))
  (next-settlement-sequence [_ jurisdiction] (get-in @a [:settlement-sequences jurisdiction] 0))
  (delivery-already-dispatched? [_ delivery-id] (boolean (get-in @a [:deliveries delivery-id :dispatched?])))
  (delivery-already-settled? [_ delivery-id] (boolean (get-in @a [:deliveries delivery-id :settled?])))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :delivery/upsert
      (swap! a update-in [:deliveries (:id value)] merge value)

      :assessment/set
      (swap! a assoc-in [:assessments (first path)] payload)

      :delivery/mark-dispatched
      (let [delivery-id (first path)
            {:keys [result delivery-patch]} (dispatch-delivery! s delivery-id)
            jurisdiction (:jurisdiction (delivery s delivery-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:dispatch-sequences jurisdiction] (fnil inc 0))
                       (update-in [:deliveries delivery-id] merge delivery-patch)
                       (update :dispatches registry/append result))))
        result)

      :delivery/mark-settled
      (let [delivery-id (first path)
            {:keys [result delivery-patch]} (settle-delivery! s delivery-id)
            jurisdiction (:jurisdiction (delivery s delivery-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:settlement-sequences jurisdiction] (fnil inc 0))
                       (update-in [:deliveries delivery-id] merge delivery-patch)
                       (update :settlements registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-deliveries [s deliveries] (when (seq deliveries) (swap! a assoc :deliveries deliveries)) s))

(defn seed-db
  "A MemStore seeded with the demo store/order/delivery set. The
  deterministic default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :assessments {}
                           :ledger [] :dispatch-sequences {} :dispatches []
                           :settlement-sequences {} :settlements []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (capability-library store/order records,
  assessment payloads, ledger facts, dispatch/settlement records) are
  stored as EDN strings so `langchain.db` doesn't expand them into
  sub-entities -- the same convention every sibling actor's store uses."
  {:delivery/id                {:db/unique :db.unique/identity}
   :shop/id                    {:db/unique :db.unique/identity}
   :order/id                   {:db/unique :db.unique/identity}
   :assessment/delivery-id     {:db/unique :db.unique/identity}
   :ledger/seq                 {:db/unique :db.unique/identity}
   :dispatch/seq               {:db/unique :db.unique/identity}
   :settlement/seq             {:db/unique :db.unique/identity}
   :dispatch-sequence/jurisdiction    {:db/unique :db.unique/identity}
   :settlement-sequence/jurisdiction  {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- delivery->tx [{:keys [id order-id store-id tracking courier recipient destination
                             pickup-day pickup-time pod-confirmed?
                             exception-raised? exception-resolved?
                             dispatched? settled?
                             cod-amount jurisdiction status dispatch-number settlement-number]}]
  (cond-> {:delivery/id id}
    order-id                       (assoc :delivery/order-id order-id)
    store-id                       (assoc :delivery/store-id store-id)
    tracking                       (assoc :delivery/tracking tracking)
    courier                        (assoc :delivery/courier courier)
    recipient                      (assoc :delivery/recipient recipient)
    destination                    (assoc :delivery/destination destination)
    pickup-day                     (assoc :delivery/pickup-day pickup-day)
    pickup-time                    (assoc :delivery/pickup-time pickup-time)
    (some? pod-confirmed?)         (assoc :delivery/pod-confirmed? pod-confirmed?)
    (some? exception-raised?)      (assoc :delivery/exception-raised? exception-raised?)
    (some? exception-resolved?)    (assoc :delivery/exception-resolved? exception-resolved?)
    (some? dispatched?)            (assoc :delivery/dispatched? dispatched?)
    (some? settled?)               (assoc :delivery/settled? settled?)
    cod-amount                     (assoc :delivery/cod-amount cod-amount)
    jurisdiction                   (assoc :delivery/jurisdiction jurisdiction)
    status                         (assoc :delivery/status status)
    dispatch-number                (assoc :delivery/dispatch-number dispatch-number)
    settlement-number              (assoc :delivery/settlement-number settlement-number)))

(def ^:private delivery-pull
  [:delivery/id :delivery/order-id :delivery/store-id :delivery/tracking :delivery/courier
   :delivery/recipient :delivery/destination :delivery/pickup-day :delivery/pickup-time
   :delivery/pod-confirmed? :delivery/exception-raised? :delivery/exception-resolved?
   :delivery/dispatched? :delivery/settled?
   :delivery/cod-amount :delivery/jurisdiction :delivery/status
   :delivery/dispatch-number :delivery/settlement-number])

(defn- pull->delivery [m]
  (when (:delivery/id m)
    {:id (:delivery/id m) :order-id (:delivery/order-id m) :store-id (:delivery/store-id m)
     :tracking (:delivery/tracking m) :courier (:delivery/courier m)
     :recipient (:delivery/recipient m) :destination (:delivery/destination m)
     :pickup-day (:delivery/pickup-day m) :pickup-time (:delivery/pickup-time m)
     :pod-confirmed? (boolean (:delivery/pod-confirmed? m))
     :exception-raised? (boolean (:delivery/exception-raised? m))
     :exception-resolved? (boolean (:delivery/exception-resolved? m))
     :dispatched? (boolean (:delivery/dispatched? m))
     :settled? (boolean (:delivery/settled? m))
     :cod-amount (:delivery/cod-amount m)
     :jurisdiction (:delivery/jurisdiction m) :status (:delivery/status m)
     :dispatch-number (:delivery/dispatch-number m) :settlement-number (:delivery/settlement-number m)}))

(defrecord DatomicStore [conn]
  Store
  (delivery [_ id]
    (pull->delivery (d/pull (d/db conn) delivery-pull [:delivery/id id])))
  (all-deliveries [_]
    (->> (d/q '[:find [?id ...] :where [?e :delivery/id ?id]] (d/db conn))
         (map #(pull->delivery (d/pull (d/db conn) delivery-pull [:delivery/id %])))
         (sort-by :id)))
  (store-of [_ store-id]
    (dec* (d/q '[:find ?r . :in $ ?sid
                :where [?e :shop/id ?sid] [?e :shop/record ?r]]
              (d/db conn) store-id)))
  (order-of [_ order-id]
    (dec* (d/q '[:find ?r . :in $ ?oid
                :where [?e :order/id ?oid] [?e :order/record ?r]]
              (d/db conn) order-id)))
  (assessment-of [_ delivery-id]
    (dec* (d/q '[:find ?p . :in $ ?did
                :where [?a :assessment/delivery-id ?did] [?a :assessment/payload ?p]]
              (d/db conn) delivery-id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (dispatch-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :dispatch/seq ?s] [?e :dispatch/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (settlement-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :settlement/seq ?s] [?e :settlement/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (next-dispatch-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :dispatch-sequence/jurisdiction ?j] [?e :dispatch-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (next-settlement-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :settlement-sequence/jurisdiction ?j] [?e :settlement-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (delivery-already-dispatched? [s delivery-id]
    (boolean (:dispatched? (delivery s delivery-id))))
  (delivery-already-settled? [s delivery-id]
    (boolean (:settled? (delivery s delivery-id))))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :delivery/upsert
      (d/transact! conn [(delivery->tx value)])

      :assessment/set
      (d/transact! conn [{:assessment/delivery-id (first path) :assessment/payload (enc payload)}])

      :delivery/mark-dispatched
      (let [delivery-id (first path)
            {:keys [result delivery-patch]} (dispatch-delivery! s delivery-id)
            jurisdiction (:jurisdiction (delivery s delivery-id))
            next-n (inc (next-dispatch-sequence s jurisdiction))]
        (d/transact! conn
                     [(delivery->tx (assoc delivery-patch :id delivery-id))
                      {:dispatch-sequence/jurisdiction jurisdiction :dispatch-sequence/next next-n}
                      {:dispatch/seq (count (dispatch-history s)) :dispatch/record (enc (get result "record"))}])
        result)

      :delivery/mark-settled
      (let [delivery-id (first path)
            {:keys [result delivery-patch]} (settle-delivery! s delivery-id)
            jurisdiction (:jurisdiction (delivery s delivery-id))
            next-n (inc (next-settlement-sequence s jurisdiction))]
        (d/transact! conn
                     [(delivery->tx (assoc delivery-patch :id delivery-id))
                      {:settlement-sequence/jurisdiction jurisdiction :settlement-sequence/next next-n}
                      {:settlement/seq (count (settlement-history s)) :settlement/record (enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-deliveries [s deliveries]
    (when (seq deliveries) (d/transact! conn (mapv delivery->tx (vals deliveries)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:stores .. :orders .. :deliveries ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [stores orders deliveries]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (when (seq stores)
       (d/transact! (:conn s) (mapv (fn [[id r]] {:shop/id id :shop/record (enc r)}) stores)))
     (when (seq orders)
       (d/transact! (:conn s) (mapv (fn [[id r]] {:order/id id :order/record (enc r)}) orders)))
     (with-deliveries s deliveries))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo store/order/delivery set -- the
  Datomic-backed analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
