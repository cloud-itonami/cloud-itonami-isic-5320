(ns courierops.governor-contract-test
  "The governor contract as executable tests -- this vertical's own
  Decision Rule, published in `docs/business-model.md`, implemented
  faithfully. The single invariant under test:

    CourierOps-LLM never dispatches or settles a delivery the Courier
    Governor would reject, `:delivery/dispatch`/`:delivery/settle`
    NEVER auto-commit at any phase, `:delivery/intake` (no direct
    capital risk) MAY auto-commit when clean, and every decision
    (commit OR hold) leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [courierops.store :as store]
            [courierops.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :courier-dispatcher :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- assess!
  "Walks `subject` through assess -> approve, leaving an assessment on
  file. Uses distinct thread-ids per call site by suffixing
  `tid-prefix`."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-assess") {:op :jurisdiction/assess :subject subject} operator)
  (approve! actor (str tid-prefix "-assess")))

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :delivery/intake :subject "delivery-1"
                   :patch {:id "delivery-1" :courier "courier-9"}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "courier-9" (:courier (store/delivery db "delivery-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest jurisdiction-assess-always-needs-approval
  (testing "assess is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :jurisdiction/assess :subject "delivery-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/assessment-of db "delivery-1")))))))

(deftest fabricated-jurisdiction-is-held
  (testing "a jurisdiction/assess proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :jurisdiction/assess :subject "delivery-1" :no-spec? true} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/assessment-of db "delivery-1")) "no assessment written"))))

(deftest delivery-dispatch-without-assessment-is-held
  (testing "delivery/dispatch before any jurisdiction assessment -> HOLD (evidence incomplete)"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :delivery/dispatch :subject "delivery-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:evidence-incomplete} (-> (store/ledger db) first :basis))))))

(deftest tracking-number-invalid-is-held-and-unoverridable
  (testing "a malformed tracking number -> HOLD, and never reaches request-approval -- the governor calls kotoba.logistics/tracking-valid? through courierops.registry, never reimplementing it"
    (let [[db actor] (fresh)
          _ (assess! actor "t5pre" "delivery-3")
          res (exec-op actor "t5" {:op :delivery/dispatch :subject "delivery-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:tracking-number-invalid} (-> (store/ledger db) last :basis)))
      (is (empty? (store/dispatch-history db))))))

(deftest store-pickup-unavailable-is-held-and-unoverridable
  (testing "a pickup moment outside the origin store's opening hours -> HOLD, and never reaches request-approval -- the governor calls kotoba.omise/pickup-available? through courierops.registry (the store-side pickup-window ground truth)"
    (let [[db actor] (fresh)
          _ (assess! actor "t6pre" "delivery-4")
          res (exec-op actor "t6" {:op :delivery/dispatch :subject "delivery-4"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:store-pickup-unavailable} (-> (store/ledger db) last :basis)))
      (is (empty? (store/dispatch-history db))))))

(deftest order-not-dispatchable-is-held-and-unoverridable
  (testing "an order still :placed (not :packed) -> HOLD, and never reaches request-approval -- the governor calls kotoba.okaimono/dispatchable? through courierops.registry (the order-lifecycle ground truth)"
    (let [[db actor] (fresh)
          _ (assess! actor "t7pre" "delivery-5")
          res (exec-op actor "t7" {:op :delivery/dispatch :subject "delivery-5"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:order-not-dispatchable} (-> (store/ledger db) last :basis)))
      (is (empty? (store/dispatch-history db))))))

(deftest pod-unconfirmed-is-held-and-unoverridable
  (testing "an unconfirmed proof-of-delivery at settlement time -> HOLD, and never reaches request-approval -- grounded in the parcel-liability regimes courierops.facts cites"
    (let [[db actor] (fresh)
          _ (assess! actor "t8pre" "delivery-6")
          _ (exec-op actor "t8dispatch" {:op :delivery/dispatch :subject "delivery-6"} operator)
          _ (approve! actor "t8dispatch")
          res (exec-op actor "t8" {:op :delivery/settle :subject "delivery-6"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:pod-unconfirmed} (-> (store/ledger db) last :basis)))
      (is (empty? (store/settlement-history db))))))

(deftest delivery-exception-unresolved-is-held-and-unoverridable
  (testing "an unresolved delivery exception -> HOLD, and never reaches request-approval, on either dispatch or settlement"
    (let [[db actor] (fresh)
          _ (assess! actor "t9pre" "delivery-7")
          res (exec-op actor "t9" {:op :delivery/dispatch :subject "delivery-7"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:delivery-exception-unresolved} (-> (store/ledger db) last :basis)))
      (is (empty? (store/dispatch-history db))))))

(deftest delivery-dispatch-always-escalates-then-human-decides
  (testing "a clean, fully-assessed, valid-tracking, open-store, packed-order, no-exception delivery still ALWAYS interrupts for human approval -- actuation/dispatch-delivery is never auto"
    (let [[db actor] (fresh)
          _ (assess! actor "t10pre" "delivery-1")
          r1 (exec-op actor "t10" {:op :delivery/dispatch :subject "delivery-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, dispatch record drafted"
        (let [r2 (approve! actor "t10")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:dispatched? (store/delivery db "delivery-1"))))
          (is (= 1 (count (store/dispatch-history db))) "one draft dispatch record"))))))

(deftest delivery-settle-always-escalates-then-human-decides
  (testing "a clean, fully-assessed, POD-confirmed, no-exception delivery still ALWAYS interrupts for human approval -- actuation/settle-delivery is never auto"
    (let [[db actor] (fresh)
          _ (assess! actor "t11pre" "delivery-1")
          r1 (exec-op actor "t11" {:op :delivery/settle :subject "delivery-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, settlement record drafted"
        (let [r2 (approve! actor "t11")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:settled? (store/delivery db "delivery-1"))))
          (is (= 1 (count (store/settlement-history db))) "one draft settlement record"))))))

(deftest delivery-dispatch-double-dispatch-is-held
  (testing "dispatching the same delivery twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (assess! actor "t12pre" "delivery-1")
          _ (exec-op actor "t12a" {:op :delivery/dispatch :subject "delivery-1"} operator)
          _ (approve! actor "t12a")
          res (exec-op actor "t12" {:op :delivery/dispatch :subject "delivery-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-dispatched} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/dispatch-history db))) "still only the one earlier dispatch"))))

(deftest delivery-settle-double-settlement-is-held
  (testing "settling the same delivery twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (assess! actor "t13pre" "delivery-1")
          _ (exec-op actor "t13a" {:op :delivery/settle :subject "delivery-1"} operator)
          _ (approve! actor "t13a")
          res (exec-op actor "t13" {:op :delivery/settle :subject "delivery-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-settled} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/settlement-history db))) "still only the one earlier settlement"))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :delivery/intake :subject "delivery-1"
                          :patch {:id "delivery-1" :courier "courier-9"}} operator)
      (exec-op actor "b" {:op :jurisdiction/assess :subject "delivery-1" :no-spec? true} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
