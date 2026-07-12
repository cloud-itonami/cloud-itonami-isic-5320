(ns courierops.registry-test
  (:require [clojure.test :refer [deftest is]]
            [courierops.registry :as r]
            [kotoba.okaimono :as okaimono]
            [kotoba.omise :as omise]))

;; --------------------- capability-library delegations ---------------------

(deftest tracking-valid-delegates-to-the-capability-library
  (is (r/tracking-valid? "1Z999AA10123456784"))
  (is (not (r/tracking-valid? "BAD#TRACK123")))
  (is (not (r/tracking-valid? "SHORT"))))

(deftest pickup-available-delegates-to-the-capability-library
  (let [shop (omise/store "st-1" "Kanda Books" "addr"
                          :hours {:mon [["09:00" "18:00"]]} :pickup-ready? true)
        paused (omise/store "st-2" "Paused" "addr"
                            :hours {:mon [["09:00" "18:00"]]} :pickup-ready? true
                            :status :suspended)]
    (is (r/pickup-available? shop :mon "10:00"))
    (is (not (r/pickup-available? shop :sun "10:00")) "closed day")
    (is (not (r/pickup-available? paused :mon "10:00")) "suspended store")))

(deftest order-dispatchable-delegates-to-the-capability-library
  (let [lines [(okaimono/line "sku-1" "Beans" 1 900)]
        placed (okaimono/order "ok-1" "st-1" {} lines)
        packed (-> placed (okaimono/advance :confirmed) (okaimono/advance :packed))]
    (is (r/order-dispatchable? packed))
    (is (not (r/order-dispatchable? placed)))
    (is (not (r/order-dispatchable? (okaimono/advance placed :cancelled))))))

;; --------------------- register-delivery-dispatch ---------------------

(deftest dispatch-is-a-draft-not-a-real-dispatch
  (let [result (r/register-delivery-dispatch "delivery-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest dispatch-assigns-dispatch-number
  (let [result (r/register-delivery-dispatch "delivery-1" "JPN" 7)]
    (is (= (get result "dispatch_number") "JPN-DSP-000007"))
    (is (= (get-in result ["record" "delivery_id"]) "delivery-1"))
    (is (= (get-in result ["record" "kind"]) "delivery-dispatch-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest dispatch-validation-rules
  (is (thrown? Exception (r/register-delivery-dispatch "" "JPN" 0)))
  (is (thrown? Exception (r/register-delivery-dispatch "delivery-1" "" 0)))
  (is (thrown? Exception (r/register-delivery-dispatch "delivery-1" "JPN" -1))))

;; --------------------- register-delivery-settlement ---------------------

(deftest settlement-is-a-draft-not-a-real-settlement
  (let [result (r/register-delivery-settlement "delivery-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest settlement-assigns-settlement-number
  (let [result (r/register-delivery-settlement "delivery-1" "JPN" 7)]
    (is (= (get result "settlement_number") "JPN-STL-000007"))
    (is (= (get-in result ["record" "delivery_id"]) "delivery-1"))
    (is (= (get-in result ["record" "kind"]) "delivery-settlement-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest settlement-validation-rules
  (is (thrown? Exception (r/register-delivery-settlement "" "JPN" 0)))
  (is (thrown? Exception (r/register-delivery-settlement "delivery-1" "" 0)))
  (is (thrown? Exception (r/register-delivery-settlement "delivery-1" "JPN" -1))))

(deftest history-is-append-only
  (let [c1 (r/register-delivery-dispatch "delivery-1" "JPN" 0)
        hist (r/append [] c1)
        c2 (r/register-delivery-dispatch "delivery-2" "JPN" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-DSP-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-DSP-000001" (get-in hist2 [1 "record_id"])))))
