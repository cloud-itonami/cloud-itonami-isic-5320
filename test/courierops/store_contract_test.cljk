(ns courierops.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a
  configuration change, not a rewrite."
  (:require [clojure.test :refer [deftest is testing]]
            [courierops.store :as store]
            [kotoba.okaimono :as okaimono]
            [kotoba.omise :as omise]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "JPN" (:jurisdiction (store/delivery s "delivery-1"))))
      (is (= "1Z999AA10123456784" (:tracking (store/delivery s "delivery-1"))))
      (is (true? (:pod-confirmed? (store/delivery s "delivery-1"))))
      (is (= "BAD#TRACK123" (:tracking (store/delivery s "delivery-3"))))
      (is (= :sun (:pickup-day (store/delivery s "delivery-4"))))
      (is (= "ok-2" (:order-id (store/delivery s "delivery-5"))))
      (is (false? (:pod-confirmed? (store/delivery s "delivery-6"))))
      (is (true? (:exception-raised? (store/delivery s "delivery-7"))))
      (is (false? (:exception-resolved? (store/delivery s "delivery-7"))))
      (is (false? (:dispatched? (store/delivery s "delivery-1"))))
      (is (false? (:settled? (store/delivery s "delivery-1"))))
      (is (= ["delivery-1" "delivery-2" "delivery-3" "delivery-4" "delivery-5" "delivery-6" "delivery-7"]
             (mapv :id (store/all-deliveries s))))
      (testing "capability-library records read back as-is"
        (let [shop (store/store-of s "st-kanda")
              order (store/order-of s "ok-1")]
          (is (= "Kanda Books" (:omise/name shop)))
          (is (omise/pickup-available? shop :mon "10:00"))
          (is (okaimono/dispatchable? order))
          (is (not (okaimono/dispatchable? (store/order-of s "ok-2"))))))
      (is (nil? (store/assessment-of s "delivery-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/dispatch-history s)))
      (is (= [] (store/settlement-history s)))
      (is (zero? (store/next-dispatch-sequence s "JPN")))
      (is (zero? (store/next-settlement-sequence s "JPN")))
      (is (false? (store/delivery-already-dispatched? s "delivery-1")))
      (is (false? (store/delivery-already-settled? s "delivery-1"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :delivery/upsert
                                 :value {:id "delivery-1" :courier "courier-9"}})
        (is (= "courier-9" (:courier (store/delivery s "delivery-1"))))
        (is (= "1Z999AA10123456784" (:tracking (store/delivery s "delivery-1"))) "unrelated field preserved"))
      (testing "assessment payloads commit and read back"
        (store/commit-record! s {:effect :assessment/set :path ["delivery-1"]
                                 :payload {:jurisdiction "JPN" :checklist ["a" "b"]}})
        (is (= {:jurisdiction "JPN" :checklist ["a" "b"]} (store/assessment-of s "delivery-1"))))
      (testing "delivery dispatch drafts a record and advances the dispatch sequence"
        (store/commit-record! s {:effect :delivery/mark-dispatched :path ["delivery-1"]})
        (is (= "JPN-DSP-000000" (get (first (store/dispatch-history s)) "record_id")))
        (is (= "delivery-dispatch-draft" (get (first (store/dispatch-history s)) "kind")))
        (is (true? (:dispatched? (store/delivery s "delivery-1"))))
        (is (= 1 (count (store/dispatch-history s))))
        (is (= 1 (store/next-dispatch-sequence s "JPN")))
        (is (true? (store/delivery-already-dispatched? s "delivery-1"))))
      (testing "delivery settlement drafts a record and advances the settlement sequence"
        (store/commit-record! s {:effect :delivery/mark-settled :path ["delivery-1"]})
        (is (= "JPN-STL-000000" (get (first (store/settlement-history s)) "record_id")))
        (is (= "delivery-settlement-draft" (get (first (store/settlement-history s)) "kind")))
        (is (true? (:settled? (store/delivery s "delivery-1"))))
        (is (= 1 (count (store/settlement-history s))))
        (is (= 1 (store/next-settlement-sequence s "JPN")))
        (is (true? (store/delivery-already-settled? s "delivery-1"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/delivery s "nope")))
    (is (nil? (store/store-of s "nope")))
    (is (nil? (store/order-of s "nope")))
    (is (= [] (store/all-deliveries s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/dispatch-history s)))
    (is (= [] (store/settlement-history s)))
    (is (zero? (store/next-dispatch-sequence s "JPN")))
    (is (zero? (store/next-settlement-sequence s "JPN")))
    (store/with-deliveries s {"x" {:id "x" :order-id "ok-x" :store-id "st-x"
                                   :tracking "1Z999AA10123456784" :courier "c"
                                   :recipient "r" :destination "d"
                                   :pickup-day :mon :pickup-time "10:00"
                                   :pod-confirmed? true
                                   :exception-raised? false :exception-resolved? false
                                   :dispatched? false :settled? false
                                   :cod-amount 0 :jurisdiction "JPN" :status :intake}})
    (is (= "c" (:courier (store/delivery s "x"))))))
