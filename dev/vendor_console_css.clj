(ns vendor-console-css
  "Regenerates `resources/foodmachmfg/console.css` — the vendored
  stylesheet `foodmachmfg.render-html` inlines into the operator
  console.

  WHY THIS IS VENDORED RATHER THAN A DEPENDENCY.
  `docs/samples/operator-console.html` must be reproducible offline: the
  console is a build artifact whose determinism check compares whole
  files, and `clojure -M:dev:render-html` has to emit the same bytes on a
  machine with no network and no populated `~/.gitlibs`. A `:git/sha`
  dependency on jp-go-digital-design-system would make the CSS — and so
  the artifact — resolvable only when git coordinates can be fetched.
  Vendoring the bytes is also what this repo already does for its product
  face: `docs/index.html` carries the same DADS CSS inline.

  WHAT IS KEPT.
  jp-go-dds ships `dds.css` (the デジタル庁デザインシステム tokens plus its
  component classes) and `jp-go-dds.skin`, a compatibility skin written
  specifically for these cloud-itonami operator consoles — it styles plain
  semantic markup (`h1`/`table`/`code`/`footer`) and the small class
  vocabulary the consoles use (`.card` `.bar` `.badge` `.ok` `.warn`
  `.critical` `.muted`). The console emits no `.dads-*` class at all, so
  every `.dads-*` component rule is unreachable by construction. This
  script keeps the token layer, the element-level rules and the skin, and
  drops the component rules — roughly 49 KB of CSS that could never match
  a selector on the page.

  The drop is verified, not assumed: the script fails rather than writes
  if the filtered stylesheet still references a custom property nothing
  defines, or if any selector the console actually relies on went missing.

  Usage (only when refreshing the vendored copy):
    clojure -M:vendor-css"
  (:require [clojure.java.io :as io]
            [kotoba.lang.text :as str]
            [jp-go-dds.skin]))

(def ^:private out-path "resources/foodmachmfg/console.css")

(def ^:private required-fragments
  "Things the console's own markup depends on, asserted as substrings of
  the filtered stylesheet rather than as exact selectors -- upstream
  rewrites selector shape (`th,td` became `th:not(.dads-table__th),...`)
  without changing what it styles, and an assertion that tracked the old
  spelling would fail on a healthy refresh while a real loss slipped by.

  These are deliberately shape-independent: a class the console emits, or
  a declaration only the console's own rules carry."
  [".ok" ".warn" ".critical" ".muted" ".bar" ".badge" ".card"
   "border-collapse" "font-variant-numeric" "footer"])

(defn- top-level-blocks
  "Splits a stylesheet into top-level `[selector whole-block]` pairs by
  brace depth. Adequate here because the input is generated CSS with no
  braces inside string literals."
  [css]
  (loop [i 0, depth 0, start 0, sel nil, out []]
    (if (>= i (count css))
      (cond-> out
        (str/blank? (subs css start)) identity)
      (let [ch (.charAt ^String css i)]
        (cond
          (= ch \{) (recur (inc i) (inc depth) start
                           (if (zero? depth) (subs css start i) sel) out)
          (= ch \}) (if (= 1 depth)
                      (recur (inc i) 0 (inc i) nil
                             (conj out [(str/trim sel) (subs css start (inc i))]))
                      (recur (inc i) (dec depth) start sel out))
          :else (recur (inc i) depth start sel out))))))

(defn- rule-selectors
  "Every rule selector in a block, ignoring at-rule preludes -- an
  `@media (hover: hover)` wrapper is not itself a selector, so it must
  not be counted when deciding whether a block is reachable."
  [block]
  (->> (str/replace block #"@[a-zA-Z-]+[^{]*\{" "")
       (re-seq #"([^{}]+)\{")
       (map (comp str/trim second))
       (remove str/blank?)))

(defn- component-selector?
  "True when the selector can only match a `.dads-*` component element.

  A `.dads-*` mention inside `:not(...)` means the opposite -- the skin
  writes `table:not(.dads-table__table)` precisely to style the plain
  tables this console emits -- so negations are stripped before asking.
  Treating those as component rules silently drops the console's whole
  table style while every other check still passes."
  [selector]
  (-> selector
      (str/replace #":not\([^)]*\)" "")
      (str/includes? ".dads-")))

(defn- unreachable?
  "True when every selector in the block is a `.dads-*` component class.
  A block that mixes reachable selectors in is kept whole."
  [[_ block]]
  (let [selectors (rule-selectors block)]
    (and (seq selectors)
         (every? component-selector? selectors))))

(defn filter-css
  "Keeps the token layer, element rules and skin; drops unreachable
  `.dads-*` component rules."
  [css]
  (->> (top-level-blocks css)
       (remove unreachable?)
       (map second)
       (str/join)))

(defn- undefined-vars
  "Custom properties the stylesheet references but never defines."
  [css]
  (let [defined (set (map second (re-seq #"(--[A-Za-z0-9_-]+)\s*:" css)))
        used (set (map second (re-seq #"var\((--[A-Za-z0-9_-]+)" css)))]
    (sort (remove defined used))))

(defn -main [& _]
  (let [full (jp-go-dds.skin/dds+skin)
        kept (filter-css full)
        missing-vars (undefined-vars kept)
        missing-frags (remove #(str/includes? kept %) required-fragments)]
    (when (seq missing-vars)
      (throw (ex-info "refusing to vendor: filtered CSS references undefined custom properties"
                      {:undefined missing-vars
                       :hint "a kept rule references a token defined only inside a dropped block"})))
    (when (seq missing-frags)
      (throw (ex-info "refusing to vendor: filtered CSS lost something the console depends on"
                      {:missing missing-frags
                       :present-upstream (filterv #(str/includes? full %) missing-frags)
                       :hint (str "present-upstream means the filter dropped it; otherwise "
                                  "upstream renamed it and required-fragments needs updating")})))
    (io/make-parents out-path)
    (spit out-path
          (str "/* Vendored for offline, reproducible builds -- do not edit by hand.\n"
               " *\n"
               " * Source:    kotoba-lang/jp-go-digital-design-system\n"
               " *            (jp-go-dds.skin/dds+skin = vendored dds.css + the\n"
               " *            operator-console compatibility skin)\n"
               " * Upstream:  digital-go-jp/design-system (デジタル庁デザインシステム)\n"
               " * Filter:    top-level blocks whose selectors are all `.dads-*`\n"
               " *            component classes are dropped -- the console emits no\n"
               " *            `.dads-*` class, so they cannot match. Token layer,\n"
               " *            element rules and skin are kept verbatim.\n"
               " * Regenerate: clojure -M:vendor-css  (see dev/vendor_console_css.clj)\n"
               " */\n")
          :encoding "UTF-8")
    (spit out-path kept :append true :encoding "UTF-8")
    (println "wrote" out-path
             (str "(" (count full) " chars upstream -> " (count kept) " chars kept, "
                  (- (count (top-level-blocks full)) (count (remove unreachable? (top-level-blocks full))))
                  " unreachable .dads-* blocks dropped, "
                  "0 undefined custom properties)"))))
