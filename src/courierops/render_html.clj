(ns courierops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave5 rollout ledger): this repo previously had NO demo page and no
  generator at all. This namespace drives the REAL actor stack
  (`courierops.operation` -> `courierops.governor` -> `courierops.
  store`) through a scenario adapted from this repo's own `courierops.
  sim` demo driver (`clojure -M:dev:run`, confirmed by actually running
  it before this file was written -- this repo's own sim driver uses
  ids that DO match `courierops.store/demo-data`'s seeded deliveries
  exactly, and every disposition it produces (commit / escalate+approve
  / HARD hold, and the exact `:rule` on each hold) matches
  `courierops.governor`'s own documented seven checks precisely, so it
  was safe to reuse rather than author from scratch), rendered
  deterministically -- no invented numbers, no timestamps in the page
  content, byte-identical across reruns against the same seed (verified
  by diffing two consecutive runs before shipping).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [courierops.store :as store]
            [courierops.operation :as op]
            [langgraph.graph :as g]))

;; ----------------------------- harness (unchanged across every repo
;; in this cluster -- do not rewrite, only copy) -----------------------

(def ^:private operator
  {:actor-id "op-1" :actor-role :courier-dispatcher :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every disposition
  this actor can reach, using ONLY real delivery ids from
  `courierops.store/demo-data`:

  delivery-1 (JPN, clean) walks the full clean lifecycle: a
  `:delivery/intake` directory-normalization patch is a phase-3,
  no-capital-risk auto-commit (governor clean, `:delivery/intake` is
  the ONLY op in phase 3's `:auto` set); `:jurisdiction/assess` (JPN
  has a real spec-basis in `courierops.facts`) ALWAYS escalates (never
  auto-eligible, at any phase) and is approved by a human courier
  dispatcher; `:delivery/dispatch` and `:delivery/settle` -- the two
  REAL-WORLD actuation events this actor performs (a real courier
  dispatch / a real delivery settlement, cash-on-delivery included) --
  ALSO ALWAYS escalate (the governor's own `high-stakes` gate AND the
  phase table agree, independently, that actuation is never auto, at
  any phase) and are each approved, producing one draft delivery-
  dispatch record (`JPN-DSP-000000`) and one draft delivery-settlement
  record (`JPN-STL-000000`).

  Then six DISTINCT HARD-hold reasons, none of which ever reach a human
  (a human approver cannot override a HARD violation) -- covering every
  one of `courierops.governor`'s subject-specific checks (spec-basis,
  tracking-number, store-pickup-window, order-lifecycle, POD,
  delivery-exception), each isolated on the ONE delivery `courierops.
  store/demo-data`'s own comments say it was seeded for:
    - delivery-2 (jurisdiction ATL, not in `courierops.facts/catalog`):
      `:jurisdiction/assess` HARD-holds on `:no-spec-basis` -- the
      advisor may not invent a jurisdiction's courier-operation/
      parcel-liability-disclosure requirements.
    - delivery-3 (tracking `BAD#TRACK123`, structurally invalid):
      assessed first (clean escalate+approve, so evidence is on file
      and this HARD hold below is isolated to the tracking-number
      check alone), then `:delivery/dispatch` HARD-holds on
      `:tracking-number-invalid` -- the governor independently
      re-verifies the delivery's own tracking number via
      `kotoba.logistics/tracking-valid?`, never trusting the advisor's
      confidence alone.
    - delivery-4 (pickup day `:sun`, store closed): assessed first,
      then `:delivery/dispatch` HARD-holds on
      `:store-pickup-unavailable` -- the governor independently
      re-verifies the origin store is open and pickup-ready via
      `kotoba.omise/pickup-available?`.
    - delivery-5 (underlying order `ok-2` still `:placed`, never
      packed): assessed first, then `:delivery/dispatch` HARD-holds on
      `:order-not-dispatchable` -- the governor independently
      re-verifies the order has reached `:packed` via
      `kotoba.okaimono/dispatchable?`.
    - delivery-6 (`:pod-confirmed? false`): assessed first, then
      DISPATCHED cleanly (escalate+approve, producing draft
      `JPN-DSP-000001`) so the HARD hold below is isolated to the POD
      check alone, then `:delivery/settle` HARD-holds on
      `:pod-unconfirmed` -- no settlement without a proof-of-delivery
      on file.
    - delivery-7 (`:exception-raised? true`, `:exception-resolved?
      false`): assessed first, then `:delivery/dispatch` HARD-holds on
      `:delivery-exception-unresolved` -- an open delivery exception
      blocks both dispatch and settlement, un-overridably.

  Returns the resulting store -- every field `render` below reads is
  real governor/store output, not a hand-typed copy."
  []
  (let [db (store/seed-db)
        actor (op/build db)]

    ;; delivery-1: clean directory-normalization patch -- phase-3
    ;; auto-commit, no capital risk yet.
    (exec! actor "d1-intake" {:op :delivery/intake :subject "delivery-1"
                               :patch {:id "delivery-1" :courier "courier-7"}})

    ;; delivery-1: jurisdiction courier-operation/parcel-liability-
    ;; disclosure assessment (JPN has a real spec-basis) -- ALWAYS
    ;; escalates, approved by a human.
    (exec! actor "d1-assess" {:op :jurisdiction/assess :subject "delivery-1"})
    (approve! actor "d1-assess")

    ;; delivery-1: REAL courier dispatch (actuation/dispatch-delivery,
    ;; a real courier physically rides to the store) -- ALWAYS
    ;; escalates regardless of phase or confidence, approved by a human
    ;; courier dispatcher.
    (exec! actor "d1-dispatch" {:op :delivery/dispatch :subject "delivery-1"})
    (approve! actor "d1-dispatch")

    ;; delivery-1: REAL delivery settlement (actuation/settle-delivery,
    ;; real money moves) -- ALWAYS escalates, approved by a human.
    (exec! actor "d1-settle" {:op :delivery/settle :subject "delivery-1"})
    (approve! actor "d1-settle")

    ;; delivery-2 (ATL): no official spec-basis in courierops.facts ->
    ;; HARD hold on :no-spec-basis, never reaches a human.
    (exec! actor "d2-assess" {:op :jurisdiction/assess :subject "delivery-2" :no-spec? true})

    ;; delivery-3: assess JPN first (clean escalate+approve) so
    ;; evidence is on file and the tracking-number-invalid hold below
    ;; is isolated.
    (exec! actor "d3-assess" {:op :jurisdiction/assess :subject "delivery-3"})
    (approve! actor "d3-assess")

    ;; delivery-3: malformed tracking number (`BAD#TRACK123`) -> HARD
    ;; hold on :tracking-number-invalid, never reaches a human.
    (exec! actor "d3-dispatch" {:op :delivery/dispatch :subject "delivery-3"})

    ;; delivery-4: assess JPN first (clean escalate+approve).
    (exec! actor "d4-assess" {:op :jurisdiction/assess :subject "delivery-4"})
    (approve! actor "d4-assess")

    ;; delivery-4: pickup day :sun, store closed -> HARD hold on
    ;; :store-pickup-unavailable, never reaches a human.
    (exec! actor "d4-dispatch" {:op :delivery/dispatch :subject "delivery-4"})

    ;; delivery-5: assess JPN first (clean escalate+approve).
    (exec! actor "d5-assess" {:op :jurisdiction/assess :subject "delivery-5"})
    (approve! actor "d5-assess")

    ;; delivery-5: underlying order ok-2 still :placed, never packed ->
    ;; HARD hold on :order-not-dispatchable, never reaches a human.
    (exec! actor "d5-dispatch" {:op :delivery/dispatch :subject "delivery-5"})

    ;; delivery-6: assess JPN first (clean escalate+approve).
    (exec! actor "d6-assess" {:op :jurisdiction/assess :subject "delivery-6"})
    (approve! actor "d6-assess")

    ;; delivery-6: clean dispatch (all four dispatch-time checks pass)
    ;; -- ALWAYS escalates, approved by a human, isolating the
    ;; POD-unconfirmed hold below to the settlement check alone.
    (exec! actor "d6-dispatch" {:op :delivery/dispatch :subject "delivery-6"})
    (approve! actor "d6-dispatch")

    ;; delivery-6: :pod-confirmed? false in the seed data -> HARD hold
    ;; on :pod-unconfirmed, never reaches a human.
    (exec! actor "d6-settle" {:op :delivery/settle :subject "delivery-6"})

    ;; delivery-7: assess JPN first (clean escalate+approve).
    (exec! actor "d7-assess" {:op :jurisdiction/assess :subject "delivery-7"})
    (approve! actor "d7-assess")

    ;; delivery-7: :exception-raised? true / :exception-resolved? false
    ;; in the seed data -> HARD hold on :delivery-exception-unresolved,
    ;; never reaches a human.
    (exec! actor "d7-dispatch" {:op :delivery/dispatch :subject "delivery-7"})

    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger subject-id]
  (last (filter #(= (:subject %) subject-id) ledger)))

(defn- status-cell [ledger subject-id]
  (let [f (last-fact-for ledger subject-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-granted (:t f)) "<span class=\"ok\">approved &amp; committed</span>"
      (= :governor-hold (:t f))
      (let [rule (-> f :violations first :rule)]
        (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>"))
      (= :approval-requested (:t f)) "<span class=\"warn\">awaiting approval</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- delivery-row [ledger {:keys [id store-id order-id tracking pickup-day pickup-time
                                     pod-confirmed? exception-raised? exception-resolved?
                                     dispatched? settled? jurisdiction]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s %s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc order-id) (esc store-id) (esc jurisdiction) (esc tracking)
          (esc (some-> pickup-day name)) (esc pickup-time)
          (if pod-confirmed? "<span class=\"ok\">confirmed</span>" "<span class=\"warn\">unconfirmed</span>")
          (if (and exception-raised? (not exception-resolved?))
            "<span class=\"critical\">open</span>"
            "<span class=\"ok\">clear</span>")
          (str (if dispatched? "dispatched" "not dispatched") " / " (if settled? "settled" "not settled"))
          (status-cell ledger id)))

(defn- ledger-row [{:keys [t op subject disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc subject)
          (esc (or (some->> basis (map #(if (keyword? %) (name %) %)) (str/join ", "))
                    (some-> disposition name) ""))))

(defn- record-row [prefix {:strs [record_id delivery_id jurisdiction kind immutable]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc prefix) (esc record_id) (esc delivery_id) (esc jurisdiction)
          (if immutable "<span class=\"ok\">immutable draft</span>" (esc kind))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract
  ;; (`courierops.governor`/`courierops.phase`) -- documentation of
  ;; fixed behavior, not runtime telemetry, so it is legitimately
  ;; hand-described rather than derived from a live run.
  ["        <tr><td><code>:delivery/intake</code></td><td><span class=\"ok\">phase-3 auto-commit when clean, no capital risk yet -- the ONLY auto-eligible op in this domain</span></td></tr>"
   "        <tr><td><code>:jurisdiction/assess</code></td><td><span class=\"warn\">ALWAYS human approval &middot; spec-basis independently checked against <code>courierops.facts</code>, never fabricated</span></td></tr>"
   "        <tr><td><code>:delivery/dispatch</code></td><td><span class=\"warn\">ALWAYS human approval &middot; real courier act (actuation/dispatch-delivery) &middot; tracking-number validity, store pickup-window, order dispatchability, unresolved-exception and double-dispatch guard all independently enforced, never auto at any phase</span></td></tr>"
   "        <tr><td><code>:delivery/settle</code></td><td><span class=\"warn\">ALWAYS human approval &middot; real courier act (actuation/settle-delivery) &middot; proof-of-delivery confirmation, unresolved-exception and double-settlement guard independently enforced, never auto at any phase</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        deliveries (store/all-deliveries db)
        delivery-rows (str/join "\n" (map (partial delivery-row ledger) deliveries))
        ledger-rows (str/join "\n" (map ledger-row ledger))
        dispatch-rows (str/join "\n" (map (partial record-row "dispatch") (store/dispatch-history db)))
        settlement-rows (str/join "\n" (map (partial record-row "settlement") (store/settlement-history db)))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-5320 &middot; community last-mile courier</title><style>\n"
     "table { width: 100%; border-collapse: collapse; font-size: 14px; }\n"
     ".ok { color: #137a3f; }\n"
     "body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }\n"
     "header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }\n"
     "th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }\n"
     "h2 { margin-top: 0; font-size: 15px; }\n"
     ".warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }\n"
     "main { max-width: 980px; margin: 24px auto; padding: 0 20px; }\n"
     "header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }\n"
     ".muted { color: #888; font-size: 13px; }\n"
     ".critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }\n"
     ".card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }\n"
     ".err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }\n"
     "th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }\n"
     "header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }\n"
     "code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }\n"
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Community Last-Mile Courier (ISIC 5320) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · dispatch/settlement always human-approved</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Deliveries</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>courierops.store</code> via <code>courierops.render-html</code> (<code>clojure -M:dev:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Delivery</th><th>Order</th><th>Store</th><th>Jurisdiction</th><th>Tracking</th><th>Pickup window</th><th>POD</th><th>Exception</th><th>Dispatch / Settle</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     delivery-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Draft delivery-dispatch / delivery-settlement records</h2>\n"
     "    <p class=\"muted\">Unsigned drafts only — the courier operator's own act of dispatching/settling is outside this actor's authority (see README <code>Actuation</code>).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Kind</th><th>Record id</th><th>Delivery</th><th>Jurisdiction</th><th>Status</th></tr></thead>\n"
     "      <tbody>\n"
     dispatch-rows (when (seq dispatch-rows) "\n")
     settlement-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Courier Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden by a human approver. Jurisdiction spec-basis, tracking-number validity, store pickup-window, order dispatchability, proof-of-delivery confirmation and unresolved delivery exceptions are independently recomputed, never trusted from the advisor's proposal; a real courier dispatch or delivery settlement is always a human courier dispatcher's call, at every rollout phase.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every proposal, hold and commit this scenario produced.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Subject</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     ledger-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (spit out html)
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count (store/dispatch-history db)) "dispatch drafts,"
             (count (store/settlement-history db)) "settlement drafts )")))
