(ns courierops.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean delivery through
  intake -> jurisdiction assessment -> delivery dispatch (escalate/
  approve/commit) -> delivery settlement (escalate/approve/commit),
  then shows HARD-hold scenarios: a jurisdiction with no spec-basis, an
  invalid tracking number, a closed store pickup window, an
  undispatchable order, an unconfirmed proof-of-delivery, an unresolved
  delivery exception, a double dispatch, and a double settlement.

  Each check is exercised directly and independently below, one
  delivery per HARD-hold scenario, following the SAME 'exercise the
  failure mode directly, never only via a happy-path actuation'
  discipline every sibling actor's sim establishes."
  (:require [langgraph.graph :as g]
            [courierops.store :as store]
            [courierops.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :courier-dispatcher :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== delivery/intake delivery-1 (JPN, clean) ==")
    (println (exec-op actor "t1" {:op :delivery/intake :subject "delivery-1"
                                  :patch {:id "delivery-1" :courier "courier-7"}} operator))

    (println "== jurisdiction/assess delivery-1 (escalates -- human approves) ==")
    (println (exec-op actor "t2" {:op :jurisdiction/assess :subject "delivery-1"} operator))
    (println (approve! actor "t2"))

    (println "== delivery/dispatch delivery-1 (always escalates -- actuation/dispatch-delivery) ==")
    (let [r (exec-op actor "t3" {:op :delivery/dispatch :subject "delivery-1"} operator)]
      (println r)
      (println "-- human courier dispatcher approves --")
      (println (approve! actor "t3")))

    (println "== delivery/settle delivery-1 (always escalates -- actuation/settle-delivery) ==")
    (let [r (exec-op actor "t4" {:op :delivery/settle :subject "delivery-1"} operator)]
      (println r)
      (println "-- human courier dispatcher approves --")
      (println (approve! actor "t4")))

    (println "== jurisdiction/assess delivery-2 (no spec-basis -> HARD hold) ==")
    (println (exec-op actor "t5" {:op :jurisdiction/assess :subject "delivery-2" :no-spec? true} operator))

    (println "== jurisdiction/assess delivery-3 (escalates -- human approves; sets up the tracking-number-invalid test) ==")
    (println (exec-op actor "t6" {:op :jurisdiction/assess :subject "delivery-3"} operator))
    (println (approve! actor "t6"))

    (println "== delivery/dispatch delivery-3 (malformed tracking number -> HARD hold) ==")
    (println (exec-op actor "t7" {:op :delivery/dispatch :subject "delivery-3"} operator))

    (println "== jurisdiction/assess delivery-4 (escalates -- human approves; sets up the store-pickup-window test) ==")
    (println (exec-op actor "t8" {:op :jurisdiction/assess :subject "delivery-4"} operator))
    (println (approve! actor "t8"))

    (println "== delivery/dispatch delivery-4 (store closed at pickup moment -> HARD hold) ==")
    (println (exec-op actor "t9" {:op :delivery/dispatch :subject "delivery-4"} operator))

    (println "== jurisdiction/assess delivery-5 (escalates -- human approves; sets up the order-not-dispatchable test) ==")
    (println (exec-op actor "t10" {:op :jurisdiction/assess :subject "delivery-5"} operator))
    (println (approve! actor "t10"))

    (println "== delivery/dispatch delivery-5 (order still :placed -> HARD hold) ==")
    (println (exec-op actor "t11" {:op :delivery/dispatch :subject "delivery-5"} operator))

    (println "== jurisdiction/assess delivery-6 (escalates -- human approves; sets up the pod-unconfirmed test) ==")
    (println (exec-op actor "t12" {:op :jurisdiction/assess :subject "delivery-6"} operator))
    (println (approve! actor "t12"))

    (println "== delivery/dispatch delivery-6 (clean dispatch -- escalates -- human approves) ==")
    (println (exec-op actor "t13" {:op :delivery/dispatch :subject "delivery-6"} operator))
    (println (approve! actor "t13"))

    (println "== delivery/settle delivery-6 (POD unconfirmed -> HARD hold) ==")
    (println (exec-op actor "t14" {:op :delivery/settle :subject "delivery-6"} operator))

    (println "== jurisdiction/assess delivery-7 (escalates -- human approves; sets up the delivery-exception test) ==")
    (println (exec-op actor "t15" {:op :jurisdiction/assess :subject "delivery-7"} operator))
    (println (approve! actor "t15"))

    (println "== delivery/dispatch delivery-7 (unresolved delivery exception -> HARD hold) ==")
    (println (exec-op actor "t16" {:op :delivery/dispatch :subject "delivery-7"} operator))

    (println "== delivery/dispatch delivery-1 AGAIN (double-dispatch -> HARD hold) ==")
    (println (exec-op actor "t17" {:op :delivery/dispatch :subject "delivery-1"} operator))

    (println "== delivery/settle delivery-1 AGAIN (double-settlement -> HARD hold) ==")
    (println (exec-op actor "t18" {:op :delivery/settle :subject "delivery-1"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft delivery-dispatch records ==")
    (doseq [r (store/dispatch-history db)] (println r))

    (println "== draft delivery-settlement records ==")
    (doseq [r (store/settlement-history db)] (println r))))
