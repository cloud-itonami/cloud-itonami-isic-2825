# ADR-0001: FoodMachAdvisor ⊣ Food, Beverage and Tobacco Machinery Plant Operations Governor architecture

## Status

Accepted. `cloud-itonami-isic-2825` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry, following the
verified fresh-scaffold protocol established by prior actors in this
fleet.

## Context

`cloud-itonami-isic-2825` publishes an OSS blueprint for food/
beverage/tobacco processing-machinery (industrial mixers, filling
machines, packaging machines, bottling lines and tobacco processing
machines) **plant operations coordination** (production-batch
product-type/no-load-run-speed/quantity/defect-rate data logging,
assembly/test-bench-equipment maintenance scheduling, safety-concern
flagging, and outbound product shipment coordination). Like every
actor in this fleet, the blueprint alone is not an implementation:
this ADR records the governed-actor architecture that promotes it to
real, tested code, following the same langgraph StateGraph +
independent Governor + Phase 0->3 rollout pattern established across
the cloud-itonami fleet.

The closest domain analogs are `cloud-itonami-isic-2826` (Manufacture
of machinery for textile, apparel and leather production) and
`cloud-itonami-isic-2818` (Manufacture of power-driven hand tools):
all three are back-office coordination actors for a fixed
manufacturing plant with electromechanically-assembled, test-bench-
verified finished-goods output and a real physical/worker safety
dimension, and all three share the same four-op shape
(`:log-production-batch`/`:schedule-maintenance`/`:flag-safety-
concern`/`:coordinate-shipment`), the same two-entity verified/
registered gate structure (equipment for maintenance scheduling, batch
for shipment coordination), and the same permanent equipment-actuation
and certification-authority blocks. This build mirrors
`cloud-itonami-isic-2826`'s architecture closely but adapts the hazard
profile, equipment vocabulary, and product taxonomy to the food/
beverage/tobacco processing-machinery plant: its finished goods are
processing machinery for food, beverage and tobacco manufacturers
(industrial mixers, filling machines, packaging machines, bottling
lines, tobacco processing machines) rather than textile/apparel/
leather production machinery, so its equipment kinds are
`:mixing-equipment-assembly-line` and `:packaging-equipment-test-
bench` rather than 2826's loom-assembly line and sewing-machine test
bench, and its routine test-bench field is `:no-load-run-speed-rpm`
(plausibility-checked 0-3,000 rpm, informed by typical no-load/
running-in test speeds across this vertical's own equipment classes --
industrial food mixer drive shafts approximately 20-1,500 rpm,
packaging-machine indexing/conveyor drive motors typically under 2,000
rpm, filling-machine nozzle-head indexing drives and bottling-line
conveyor drives typically under 3,000 rpm -- and by the general
mechanical-safety framework the EU Machinery Regulation (EU)
2023/1230 establishes for this equipment class). Like 2826 and 2818,
shipment quantity is tracked in finished-unit UNITS (`:units`/
`:quantity-units`/`:shipped-units`), since food/beverage/tobacco
processing machinery is likewise discrete counted units rather than a
bulk weight.

This vertical shares 2826's DOMAIN-SPECIFIC permanent block, adapted:
like textile/apparel/leather production machinery, food/beverage/
tobacco processing machinery is subject to machinery safety
certification regimes (e.g. CE marking under the EU Machinery
Regulation (EU) 2023/1230). Unlike 2826, this vertical carries an
ADDITIONAL certification dimension specific to it: food-contact-
material compliance (e.g. EU Regulation 1935/2004, FDA 21 CFR 178, or
hygienic-design standards such as NSF/ANSI 169 / 3-A Sanitary
Standards), since this machinery directly contacts food, beverage or
tobacco product during processing. This actor is never the
certification authority for either dimension -- any proposal
(regardless of op) that declares `:issue-certification? true` is a
HARD, PERMANENT, unconditional block
(`foodmachmfg.governor/certification-authority-blocked-violations`),
the same "no phase, no human override" posture as the equipment-
actuation block. `:flag-safety-concern` correspondingly covers both a
mechanical-safety concern and a food-contact-material-compliance
concern (rather than 2826's mechanical-safety/electrical-safety/CE-
compliance triad), always escalating regardless of confidence.

This vertical has NO pre-existing `kotoba-lang/foodmachmfg`-style
capability library to wrap (verified: no such repo exists). This build
therefore uses self-contained domain logic -- pure functions in
`foodmachmfg.registry` (equipment/batch verification, shipment-
quantity recompute, product-type validation, no-load-run-speed
plausibility validation, defect-rate plausibility validation) are
re-verified independently by the governor, the same "ground truth, not
self-report" discipline established across prior actors (most
directly `cloud-itonami-isic-2826`'s `texmachmfg.registry` and
`cloud-itonami-isic-2818`'s `powertoolmfg.registry`).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:food-beverage-tobacco-machinery-plant-operations-governor`, is
grep-verified UNIQUE fleet-wide (`gh search code
"food-beverage-tobacco-machinery-plant-operations-governor" --owner
cloud-itonami`, zero hits before this repo was created).

## Decision

### Decision 1: Self-contained domain logic (no external food/beverage/tobacco-machinery-manufacturing capability library to wrap)

Unlike actors that delegate to pre-existing domain libraries, this
food/beverage/tobacco processing-machinery vertical has NO
pre-existing capability library to wrap. The equipment/batch-
verification / shipment-quantity / product-type / no-load-run-speed /
defect-rate validation functions live as pure functions in
`foodmachmfg.registry` and are re-verified independently by
`foodmachmfg.governor` -- the same "ground truth, not self-report"
discipline established across prior actors (most directly
`cloud-itonami-isic-2826`'s `texmachmfg.registry`).

### Decision 2: Coordination, not control — scope boundary at the back-office

This actor is **strictly back-office coordination** of food/beverage/
tobacco processing-machinery plant operations. It does NOT:
- Control mixing-equipment or packaging-equipment assembly/test-bench equipment directly
- Make plant-safety or certification decisions (exclusive to the human plant supervisor / accredited certification body)
- Actuate assembly/test-bench equipment
- Self-issue a machinery safety or food-contact-material compliance certification mark (e.g. CE marking under the EU Machinery Regulation, or food-contact-material compliance under EU Regulation 1935/2004 / FDA 21 CFR 178 / NSF/ANSI 169)

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human plant-supervisor
approval. This is not a replacement for the supervisor's authority or
the certification body's authority — it is a proposal-screening and
documentation layer.

**CRITICAL SAFETY BOUNDARY**: food/beverage/tobacco processing-
machinery manufacturing is a safety-critical domain (moving-part/
pinch-point hazard on assembly/test-bench lines, machinery safety
certification, food-contact-material compliance, downstream worker-
safety and food-safety consequence). Safety-concern flagging NEVER
auto-commits. All safety concerns escalate immediately to human
review.

### Decision 3: Safety-concern escalation — always human sign-off

`:flag-safety-concern` (mechanical-safety concern, food-contact-
material-compliance concern, equipment-safety concern) ALWAYS
escalates, never auto-commits. This is not a "low-stakes proposal" --
it is a circuit-breaker that must reach human authority.

### Decision 4: Two independent verified/registered gates (equipment AND batch), not one

Like `cloud-itonami-isic-2826` and `cloud-itonami-isic-2818`, this
vertical has TWO entity kinds each gating a different op:
`:schedule-maintenance` independently verifies the referenced
**equipment** unit's own `:verified?`/`:registered?` fields;
`:coordinate-shipment` independently verifies the referenced
**batch**'s own `:verified?`/`:registered?` fields. Both are the same
"plant/batch record must be independently verified/registered before
any action" HARD invariant applied to the two distinct record kinds
this domain actually has. `:coordinate-shipment` additionally
independently recomputes whether a batch's own recorded shipped-to-
date unit quantity plus the proposal's own claimed unit quantity would
exceed the batch's own recorded production quantity -- never taken on
the advisor's self-report.

### Decision 5: HARD invariants (no override)

Four HARD governor invariants (elaborated into twelve concrete checks
in `foodmachmfg.governor`, mirroring `cloud-itonami-isic-2826`'s own
elaboration of its HARD invariants into concrete checks) block
proposals and cannot be overridden by human approval:
1. Plant/batch record (equipment for maintenance, batch for shipment) must be independently verified/registered before any action is taken against it, and a shipment's quantity must independently recompute within the batch's own logged production quantity
2. Proposals must be `:effect :propose` only (never direct equipment control)
3. Direct assembly/test-bench-equipment control, equipment actuation, or self-issued machinery/food-contact-material compliance certification is permanently blocked
4. The op allowlist is closed — `:log-production-batch`/`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` only

## Consequences

(+) Food/beverage/tobacco processing-machinery plant operations
back-office now has a documented, governed, auditable coordination
layer that funnels all decisions through independent validation
before human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world actuation requires human plant-
supervisor sign-off, and no certification mark (machinery safety OR
food-contact-material compliance) can ever be self-issued.

(+) Scope is bounded and verifiable: four HARD invariants (elaborated
into twelve concrete governor checks) protect against scope creep into
unauthorized equipment operation, equipment actuation, or
certification self-issuance. Safety concerns are a circuit-breaker,
not a threshold.

(+) Safety-critical discipline is explicit: safety-concern flagging
cannot be rate-limited, suppressed, or auto-decided by phase gate.
Human review is mandatory.

(-) Still a simulation/proposal layer, not a real plant-operations
control system. Equipment actuation, line operation, and certification
issuance remain human-/institution-controlled via external channels.

(-) No integration with real plant-management databases (equipment
telemetry, batch tracking, freight dispatch, certification-body APIs)
— this is a standalone coordinator blueprint.

## Verification

- `cloud-itonami-isic-2825`: `clojure -M:test` green (all tests pass;
  see the superproject ADR and `kotoba-lang/industry` registry entry
  for the exact `Ran N tests containing M assertions, 0 failures, 0
  errors` output, verified from an independent fresh clone), `clojure
  -M:lint` clean, `clojure -M:dev:run` demo narrative exercises
  proposal submission, escalation, and every HARD-hold scenario
  directly (not-propose-effect, unknown-op, equipment-not-verified,
  batch-not-verified, shipment-quantity-exceeded, equipment-actuate-
  blocked, certification-authority-blocked, already-scheduled,
  invalid-product-type, invalid-no-load-run-speed, invalid-defect-
  rate).
- All source is `.cljc` (portable ClojureScript / JVM / nbb) — no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `clojure -M:test` resolves offline inside the monorepo checkout.
