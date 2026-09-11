(ns courierops.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:delivery/dispatch`/`:delivery/settle` must NEVER be a
  member of any phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [courierops.phase :as phase]))

(deftest delivery-dispatch-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in future entries, auto-commits a real courier dispatch"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :delivery/dispatch))
          (str "phase " n " must not auto-commit :delivery/dispatch")))))

(deftest delivery-settle-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in future entries, auto-commits a real delivery settlement"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :delivery/settle))
          (str "phase " n " must not auto-commit :delivery/settle")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-capital-risk-ops
  (testing ":delivery/intake carries no direct capital risk -- auto-eligible; it is the ONLY auto-eligible op in this domain"
    (is (= #{:delivery/intake} (:auto (get phase/phases 3))))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :delivery/intake} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :delivery/dispatch} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :delivery/settle} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :delivery/intake} :commit)))))
