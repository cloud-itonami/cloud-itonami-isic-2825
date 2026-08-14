(ns foodmachmfg.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this repo: it previously had no
  generated demo page at all. This namespace drives the REAL actor
  stack -- `foodmachmfg.operation` (a langgraph-clj StateGraph) ->
  `foodmachmfg.governor` -> `foodmachmfg.store` -- exactly the way
  `foodmachmfg.sim` does (`clojure -M:dev:run`, run and read BEFORE
  this file was written), and renders what that run actually produced.

  EVERY id, quantity, product type, hold rule and hold detail on the
  page comes from `foodmachmfg.store/sample-data!` (batch-001/002/003,
  mix-001, pack-002) or from real governor output. Nothing is typed by
  hand into the markup: the batch/equipment tables are the SSoT AFTER
  the run (so batch-001 shows its shipped-units advanced by the
  shipment this scenario actually committed), the HARD-hold table is
  the store's own append-only ledger filtered to `:t :governor-hold`,
  the action-gate table is derived from `foodmachmfg.phase/phases` and
  `foodmachmfg.governor/allowed-ops` rather than described, and the
  approval-custody table is derived by comparing each committed op's
  run-time approver against what the SSoT record kept.

  Determinism: no timestamps, no randomness, no map-iteration order --
  the store's own accessors sort by id, the ledger/history channels are
  append-ordered vectors, and every derived table sorts explicitly. Two
  consecutive runs are byte-identical.

  `-main` counts the HARD `:governor-hold` rows it is about to render
  and throws when that count is 0, so \"the page shows both
  directions\" is a build-time invariant rather than a convention: if a
  future change lets a permanently-blocked proposal through, this
  generator fails instead of quietly emitting a page with no blocks.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [foodmachmfg.governor :as governor]
            [foodmachmfg.operation :as op]
            [foodmachmfg.phase :as phase]
            [foodmachmfg.store :as store]
            [langgraph.graph :as g]))

;; ----------------------------- the run -----------------------------

(def ^:private coordinator
  "The same context `foodmachmfg.sim` drives its demo with."
  {:actor-id "coord-1" :actor-role :plant-coordinator :phase 3})

(def ^:private scenario
  "One entry per graph run, adapted from `foodmachmfg.sim`'s own demo
  driver. `:approve` names the human who resumes the interrupt when --
  and only when -- the actor actually escalates; a HARD hold never
  reaches that human, which is the point of listing both kinds here in
  one table.

  Subjects and referenced ids are the seeded ones: batches
  batch-001/002/003 and equipment mix-001/pack-002 from
  `foodmachmfg.store/sample-data!`."
  [{:tid "t1" :approve nil
    :note "clean patch — :log-production-batch is the only op any phase may auto-commit"
    :request {:op :log-production-batch :effect :propose :subject "batch-001"
              :patch {:product-type :mixing-machine :last-assessed "2026-07-14"}}}

   {:tid "t2" :approve "coord-1"
    :note "verified + registered mixing-equipment assembly line — governor clean, phase still demands a human"
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-1"
              :value {:equipment-id "mix-001" :maintenance-type :bearing-inspection
                      :scheduled-date "2026-08-01" :actuate-equipment? false}}}

   {:tid "t3" :approve "coord-1"
    :note "safety concerns ALWAYS escalate — no confidence level and no phase can auto-commit one"
    :request {:op :flag-safety-concern :effect :propose :subject "concern-1"
              :value {:equipment-id "mix-001" :severity :moderate
                      :description "混合羽根部の異常振動、安全ガードの緩み"}}}

   {:tid "t4" :approve "coord-1"
    :note "50 units against batch-001 (1000 produced, 200 already shipped) — inside its own recomputed headroom"
    :request {:op :coordinate-shipment :effect :propose :subject "ship-1"
              :value {:batch-id "batch-001" :units 50.0
                      :destination "buyer-plant-north"}}}

   ;; ---- HARD holds: none of these ever reaches the human above ----
   {:tid "t5" :approve "coord-1"
    :note "mis-wired caller: the request's own :effect is not :propose"
    :request {:op :log-production-batch :effect :direct-write :subject "batch-001"
              :patch {:product-type :mixing-machine}}}

   {:tid "t6" :approve "coord-1"
    :note "op outside the closed four-op allowlist"
    :request {:op :actuate-mixing-line :effect :propose :subject "batch-001"}}

   {:tid "t7" :approve "coord-1"
    :note "maintenance against the UNVERIFIED / unregistered packaging test bench"
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-2"
              :value {:equipment-id "pack-002" :maintenance-type :calibration
                      :scheduled-date "2026-08-01" :actuate-equipment? false}}}

   {:tid "t8" :approve "coord-1"
    :note "shipment against the UNVERIFIED / unregistered batch-003"
    :request {:op :coordinate-shipment :effect :propose :subject "ship-2"
              :value {:batch-id "batch-003" :units 100.0
                      :destination "buyer-plant-south"}}}

   {:tid "t9" :approve "coord-1"
    :note "100 units against batch-002 (300 produced, 280 already shipped) — independently recomputed as over"
    :request {:op :coordinate-shipment :effect :propose :subject "ship-3"
              :value {:batch-id "batch-002" :units 100.0
                      :destination "buyer-plant-east"}}}

   {:tid "t10" :approve "coord-1"
    :note "shipment stating no quantity against batch-001 — headroom cannot be computed, so it is not headroom"
    :request {:op :coordinate-shipment :effect :propose :subject "ship-4"
              :value {:batch-id "batch-001" :destination "buyer-plant-west"}}}

   {:tid "t11" :approve "coord-1"
    :note "PERMANENT block: directly actuating assembly/test-bench equipment"
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-3"
              :value {:equipment-id "mix-001" :maintenance-type :force-run
                      :scheduled-date "2026-09-01" :actuate-equipment? true}}}

   {:tid "t12" :approve "coord-1"
    :note "mnt-1 again — the same maintenance window may not be scheduled twice"
    :request {:op :schedule-maintenance :effect :propose :subject "mnt-1"
              :value {:equipment-id "mix-001" :maintenance-type :bearing-inspection
                      :scheduled-date "2026-08-01" :actuate-equipment? false}}}

   {:tid "t13" :approve "coord-1"
    :note "fabricated product type"
    :request {:op :log-production-batch :effect :propose :subject "batch-001"
              :patch {:product-type :unobtainium}}}

   {:tid "t14" :approve "coord-1"
    :note "implausible no-load running-in test speed"
    :request {:op :log-production-batch :effect :propose :subject "batch-001"
              :patch {:no-load-run-speed-rpm 999999.0}}}

   {:tid "t15" :approve "coord-1"
    :note "implausible defect-rate reading"
    :request {:op :log-production-batch :effect :propose :subject "batch-001"
              :patch {:defect-rate-percent 999.0}}}

   {:tid "t16" :approve "coord-1"
    :note "PERMANENT block: self-issuing a machinery / food-contact-material compliance certification"
    :request {:op :log-production-batch :effect :propose :subject "batch-001"
              :patch {:issue-certification? true}}}])

(defn- run-step!
  "Executes one scenario entry against `actor` and, if the actor
  actually escalated AND the entry names an approver, resumes the
  interrupt with that approval. Returns the entry enriched with the
  final graph state -- a HARD hold simply never gets the resume, which
  is what makes `:approved?` below a measurement rather than a label."
  [actor {:keys [tid request approve] :as entry}]
  (let [proposed (g/run* actor {:request request :context coordinator}
                         {:thread-id tid})
        escalated? (= :escalate (get-in proposed [:state :disposition]))
        final (if (and escalated? approve)
                (g/run* actor {:approval {:status :approved :by approve}}
                        {:thread-id tid :resume? true})
                proposed)]
    (assoc entry
           :escalated? escalated?
           :state (:state final))))

(defn run-demo!
  "Seeds a fresh store, builds the real FoodMachOperationActor over it
  and drives every `scenario` entry through it. Returns
  `{:db .. :steps [..]}` -- the store is the post-run SSoT and each
  step carries the graph's own final state, so every cell the renderer
  emits below is read back out of the actor, never authored."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]
    {:db db
     :steps (mapv (partial run-step! actor) scenario)}))

;; ----------------------------- derivation -----------------------------

(defn- audit-facts [step t]
  (filter #(= t (:t %)) (get-in step [:state :audit])))

(defn- step-approver
  "Who actually approved this step, per the graph's own audit channel.
  nil when nobody did -- either because it auto-committed or because a
  HARD hold meant no human was ever asked."
  [step]
  (:by (last (audit-facts step :approval-granted))))

(defn- step-rules
  "The governor rules this step tripped, in the governor's own order."
  [step]
  (mapv :rule (:violations (last (audit-facts step :governor-hold)))))

(defn- step-outcome [step]
  (let [d (get-in step [:state :disposition])]
    (cond
      (and (= :commit d) (step-approver step)) :approved-and-committed
      (= :commit d) :auto-committed
      (= :hold d) :hard-hold
      (= :escalate d) :awaiting-approval
      :else :unknown)))

(defn- committed-record
  "The SSoT entity a committed step actually wrote, looked up through
  the store's own accessors by the effect that wrote it. Used to ask
  what survived the commit -- not to re-state what was proposed."
  [db step]
  (let [subject (get-in step [:request :subject])]
    (case (get-in step [:state :record :effect])
      :batch/upsert          (store/batch db subject)
      :maintenance/schedule  (store/maintenance db subject)
      :shipment/propose      (store/shipment db subject)
      :safety-concern/flag   (first (filter #(= subject (:id %))
                                            (store/safety-concerns db)))
      nil)))

(defn- approval-custody
  "For every step that reached the SSoT: who approved it at run time,
  and whether the record the store kept carries that approver.

  `:retained?` is the presence of an `:approved-by` KEY on the stored
  entity, not a string comparison -- the requesting actor and the
  approver happen to share an id in this scenario, and comparing names
  would let that coincidence hide a dropped field."
  [db steps]
  (for [step steps
        :when (= :commit (get-in step [:state :disposition]))
        :let [record (committed-record db step)
              approver (step-approver step)]]
    {:op (get-in step [:request :op])
     :subject (get-in step [:request :subject])
     :effect (get-in step [:state :record :effect])
     :approver approver
     ;; what the graph handed the store, before the store read it
     :offered (get-in step [:state :record :payload :approved-by])
     :retained (when record (get record :approved-by))
     :retained? (boolean (and record (contains? record :approved-by)))}))

(defn- hard-holds
  "The store's own append-only ledger, filtered to HARD governor holds.
  This is the collection `-main` counts."
  [db]
  (vec (filter #(= :governor-hold (:t %)) (store/ledger db))))

(defn- action-gate
  "Derived from `foodmachmfg.governor/allowed-ops` and
  `foodmachmfg.phase/phases`: for each op this actor may route, the
  earliest phase that lets it write and whether any phase ever lets it
  auto-commit. Not a description of the contract -- a read of it."
  []
  (let [ps (sort (keys phase/phases))]
    (for [op (sort-by name governor/allowed-ops)]
      {:op op
       :first-write-phase (first (filter #(contains? (:writes (phase/phases %)) op) ps))
       :auto-phases (vec (filter #(contains? (:auto (phase/phases %)) op) ps))})))

;; ----------------------------- html -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- cell
  "nil renders as an em dash, never as an empty cell: a field the actor
  did not set must be visibly absent, not invisibly blank."
  [v]
  (if (nil? v) "<span class=\"muted\">—</span>" (esc v)))

(defn- kw [v] (if (nil? v) "<span class=\"muted\">—</span>" (str "<code>" (esc (pr-str v)) "</code>")))

(defn- flag [b] (if b "<span class=\"ok\">yes</span>" "<span class=\"critical\">no</span>"))

(defn- row [& cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table [headers rows]
  (str "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" % "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n"
       (str/join "\n" rows) "\n"
       "      </tbody>\n"
       "    </table>\n"))

(defn- section [title lede body]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       "    <p class=\"muted\">" lede "</p>\n"
       body
       "  </section>\n"))

;; --- individual sections ---

(defn- batches-section [db]
  (section
   "Production batches (SSoT after this run)"
   (str "Read back out of <code>foodmachmfg.store</code> after the scenario committed. "
        "<code>shipped-units</code> is the batch's own cumulative ground truth — the governor "
        "recomputes headroom from this column and the produced quantity, never from a shipment's "
        "own claim.")
   (table ["Batch" "Product type" "Model" "No-load run speed" "Produced" "Shipped" "Headroom"
           "Defect rate" "Verified?" "Registered?" "Last assessed"]
          (for [{:keys [id product-type model no-load-run-speed-rpm quantity-units
                        shipped-units defect-rate-percent verified? registered?
                        last-assessed]} (store/all-batches db)]
            (row (str "<code>" (esc id) "</code>")
                 (kw product-type)
                 (cell model)
                 (str (cell no-load-run-speed-rpm) " rpm")
                 (str (cell quantity-units) " units")
                 (str (cell shipped-units) " units")
                 (if (and (number? quantity-units) (number? shipped-units))
                   (str (esc (- (double quantity-units) (double shipped-units))) " units")
                   "<span class=\"muted\">not computable</span>")
                 (str (cell defect-rate-percent) " %")
                 (flag verified?)
                 (flag registered?)
                 (cell last-assessed))))))

(defn- equipment-section [db]
  (section
   "Assembly / test-bench equipment (SSoT after this run)"
   (str "A maintenance window may only be scheduled against a unit that is independently "
        "<em>verified</em> AND <em>registered</em> — the governor re-derives both from these "
        "columns and never from the advisor's rationale.")
   (table ["Unit" "Kind" "Verified?" "Registered?" "Last maintenance" "Last scheduled by this actor"]
          (for [{:keys [id kind verified? registered? last-maintenance-date
                        last-scheduled-maintenance-date]} (store/all-equipment db)]
            (row (str "<code>" (esc id) "</code>")
                 (kw kind)
                 (flag verified?)
                 (flag registered?)
                 (cell last-maintenance-date)
                 (cell last-scheduled-maintenance-date))))))

(def ^:private outcome-cell
  {:approved-and-committed "<span class=\"ok\">approved &amp; committed</span>"
   :auto-committed "<span class=\"ok\">auto-committed (phase 3)</span>"
   :hard-hold "<span class=\"critical\">HARD hold</span>"
   :awaiting-approval "<span class=\"warn\">awaiting approval</span>"
   :unknown "<span class=\"muted\">unknown</span>"})

(defn- operations-section [steps]
  (section
   "Governed operations in this run"
   (str "One row per graph run. A HARD hold is <em>not</em> a rejected approval: the run never "
        "reaches the human at all, so the approver column is empty by construction rather than "
        "by refusal. Every row below names an approver except where the actor either "
        "auto-committed or was blocked outright.")
   (table ["Thread" "Op" "Subject" "Request effect" "Confidence" "Outcome" "Approver" "What this exercises"]
          (for [step steps
                :let [outcome (step-outcome step)]]
            (row (str "<code>" (esc (:tid step)) "</code>")
                 (kw (get-in step [:request :op]))
                 (str "<code>" (esc (get-in step [:request :subject])) "</code>")
                 (kw (get-in step [:request :effect]))
                 (cell (get-in step [:state :proposal :confidence]))
                 (outcome-cell outcome)
                 (if-let [by (step-approver step)]
                   (str "<code>" (esc by) "</code>")
                   (if (= :hard-hold outcome)
                     "<span class=\"muted\">never asked (blocked)</span>"
                     "<span class=\"muted\">nobody (auto)</span>"))
                 (esc (:note step)))))))

(defn- holds-section [holds]
  (section
   "HARD holds — un-overridable governor blocks"
   (str "Straight out of the store's append-only ledger, filtered to <code>:t :governor-hold</code>. "
        "None of these is overridable: no phase and no human approval can release one. The detail "
        "column is the governor's own text, not a restatement of it.")
   (table ["Op" "Subject" "Rules" "Confidence" "Governor detail"]
          (for [{:keys [op subject basis violations confidence]} holds]
            (row (kw op)
                 (str "<code>" (esc subject) "</code>")
                 (str/join " " (map #(str "<code class=\"critical\">" (esc (name %)) "</code>") basis))
                 (cell confidence)
                 (str/join "<br>" (map #(esc (:detail %)) violations)))))))

(defn- gate-section []
  (section
   "Action gate (Food, Beverage and Tobacco Machinery Plant Operations Governor)"
   (str "Derived at build time from <code>foodmachmfg.governor/allowed-ops</code> and "
        "<code>foodmachmfg.phase/phases</code> — if a future change adds an op to a phase's "
        "<code>:auto</code> set, this table changes with it.")
   (table ["Op" "First phase allowed to write" "Phases allowed to auto-commit"]
          (for [{:keys [op first-write-phase auto-phases]} (action-gate)]
            (row (kw op)
                 (if first-write-phase
                   (str "phase " (esc first-write-phase))
                   "<span class=\"critical\">never</span>")
                 (if (seq auto-phases)
                   (str "<span class=\"warn\">phase " (str/join ", " (map esc auto-phases)) "</span>")
                   "<span class=\"ok\">never — always a human</span>"))))))

(defn- custody-section [custody]
  (let [dropped (remove :retained? (filter :approver custody))]
    (section
     "Approval custody — who approved, and what the SSoT kept"
     (str "Each committed op, compared against the record the store actually holds. "
          "<em>Offered</em> is the approver the graph handed to "
          "<code>foodmachmfg.store/commit-record!</code>; <em>retained</em> is what came back out "
          "of the stored entity. A blank offered/retained pair on an auto-commit means nobody "
          "approved it; a filled <em>offered</em> with an empty <em>retained</em> means an "
          "approver existed and was lost.")
     (str
      (table ["Op" "Subject" "Effect" "Approver at run time" "Offered to store" "Retained in SSoT"]
             (for [{:keys [op subject effect approver offered retained retained?]} custody]
               (row (kw op)
                    (str "<code>" (esc subject) "</code>")
                    (kw effect)
                    (if approver
                      (str "<code>" (esc approver) "</code>")
                      "<span class=\"muted\">nobody — auto-committed</span>")
                    (if offered (str "<code>" (esc offered) "</code>")
                        "<span class=\"muted\">nothing offered</span>")
                    (cond
                      retained? (str "<span class=\"ok\"><code>" (esc retained) "</code></span>")
                      approver "<span class=\"critical\">dropped — key absent from the stored record</span>"
                      :else "<span class=\"muted\">n/a — nobody approved</span>"))))
      (when (seq dropped)
        (str "    <p class=\"critical\"><strong>Measured defect ("
             (count dropped) " of " (count (filter :approver custody))
             " approved commits): the approver does not survive the commit.</strong> "
             "<code>foodmachmfg.operation</code>'s <code>:request-approval</code> node puts the "
             "approver on the record's <code>:payload</code>, but "
             "<code>foodmachmfg.store/commit-record!</code> destructures only "
             "<code>[effect path value]</code> and never reads <code>:payload</code>, so the "
             "approver reaches the store and is discarded there. The ledger's "
             "<code>:actor</code> field is the <em>requesting</em> actor, not the approver, so the "
             "SSoT holds no record of who signed off. This paragraph is derived from the run "
             "above and disappears on its own once the store keeps the field.</p>\n"))))))

(defn- drafts-section [db]
  (section
   "Draft records this run produced"
   (str "Unsigned drafts built by <code>foodmachmfg.registry</code>. This actor never actuates "
        "equipment, never dispatches a carrier and never issues a certification mark — it "
        "produces the record a plant coordinator would keep, and the signature is the human's act.")
   (str
    "    <h3>Maintenance schedules</h3>\n"
    (table ["Record" "Kind" "Maintenance" "Equipment" "Immutable?"]
           (for [r (store/maintenance-history db)]
             (row (str "<code>" (esc (get r "record_id")) "</code>")
                  (esc (get r "kind"))
                  (str "<code>" (esc (get r "maintenance_id")) "</code>")
                  (str "<code>" (esc (get r "equipment_id")) "</code>")
                  (flag (get r "immutable")))))
    "    <h3>Shipment coordinations</h3>\n"
    (table ["Record" "Kind" "Shipment" "Immutable?"]
           (for [r (store/shipment-history db)]
             (row (str "<code>" (esc (get r "record_id")) "</code>")
                  (esc (get r "kind"))
                  (str "<code>" (esc (get r "shipment_id")) "</code>")
                  (flag (get r "immutable")))))
    "    <h3>Safety concerns</h3>\n"
    (table ["Concern" "Equipment" "Severity" "Description"]
           (for [c (store/safety-concerns db)]
             (row (str "<code>" (esc (:id c)) "</code>")
                  (str "<code>" (esc (:equipment-id c)) "</code>")
                  (kw (:severity c))
                  (esc (:description c))))))))

(defn- ledger-section [db]
  (section
   "Audit ledger (this run, in order)"
   (str "The append-only decision-fact log — every commit and every hold this scenario produced, "
        "in the order the actor wrote them.")
   (table ["#" "Fact" "Op" "Subject" "Actor" "Disposition" "Basis"]
          (map-indexed
           (fn [i {:keys [t op subject actor disposition basis]}]
             (row (inc i)
                  (str "<code>" (esc (name t)) "</code>")
                  (kw op)
                  (str "<code>" (esc subject) "</code>")
                  (cell actor)
                  (if (= :commit disposition)
                    "<span class=\"ok\">commit</span>"
                    "<span class=\"critical\">hold</span>")
                  (str/join ", " (map #(str "<code>" (esc (name %)) "</code>") basis))))
           (store/ledger db)))))

(defn render
  "Renders the whole document from a store `db` and the `steps`
  `run-demo!` produced. Pure: same inputs, same bytes."
  [db steps]
  (let [holds (hard-holds db)
        custody (vec (approval-custody db steps))]
    (str
     "<!doctype html>\n"
     "<html lang=\"en\"><head><meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
     "<title>cloud-itonami-isic-2825 · food/beverage/tobacco processing machinery — Operator Console</title>\n"
     "<style>" (jp-go-dds.skin/dds+skin) "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Manufacture of machinery for food, beverage and tobacco processing (ISIC 2825) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · every write proposal only · "
     (count holds) " HARD holds in this run</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>What this page is</h2>\n"
     "    <p>Generated at build time by <code>foodmachmfg.render-html</code> "
     "(<code>clojure -M:dev:render-html</code>) by running the real actor — "
     "<code>foodmachmfg.operation</code> (a langgraph-clj StateGraph) → "
     "<code>foodmachmfg.governor</code> → <code>foodmachmfg.store</code> — over the seeded plant "
     "in <code>foodmachmfg.store/sample-data!</code>. Every id, quantity and hold reason below was "
     "read back out of that run.</p>\n"
     "    <p class=\"muted\">This actor coordinates back-office plant records. It never controls "
     "assembly or test-bench equipment, never decides plant safety, and never issues a machinery "
     "safety or food-contact-material compliance certification mark — those are the plant "
     "supervisor's and the accredited certification body's exclusive authority.</p>\n"
     "  </section>\n"
     (batches-section db)
     (equipment-section db)
     (operations-section steps)
     (holds-section holds)
     (gate-section)
     (custody-section custody)
     (drafts-section db)
     (ledger-section db)
     "</main>\n"
     "<footer>\n"
     "  <p>cloud-itonami-isic-2825 · AGPL-3.0-or-later · regenerate with "
     "<code>clojure -M:dev:render-html</code>. Deterministic: no timestamps, no randomness — "
     "two consecutive runs are byte-identical.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db steps]} (run-demo!)
        holds (hard-holds db)
        custody (vec (approval-custody db steps))]
    ;; Build-time invariant, not a convention: a page that shows no
    ;; un-overridable block is not a demonstration of a governor.
    (when (zero? (count holds))
      (throw (ex-info (str "refusing to write " out
                           ": the scenario produced 0 HARD :governor-hold ledger facts, so the "
                           "page would show only the permissive direction")
                      {:out out
                       :hard-holds 0
                       :ledger-facts (count (store/ledger db))
                       :steps (count steps)})))
    (io/make-parents out)
    (spit out (render db steps) :encoding "UTF-8")
    (println "wrote" out
             (str "(" (count steps) " graph runs, "
                  (count (store/ledger db)) " ledger facts, "
                  (count holds) " HARD holds, "
                  (count (filter :approver custody)) " approved commits of which "
                  (count (filter :retained? custody)) " retained the approver, "
                  (count (store/maintenance-history db)) " maintenance drafts, "
                  (count (store/shipment-history db)) " shipment drafts, "
                  (count (store/safety-concerns db)) " safety concerns)"))))
