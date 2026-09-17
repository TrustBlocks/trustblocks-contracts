(ns com.trustblocks.template.render
  "Renders a TemplateMark grammar (text/grammar.tem.md) against a contract
  instance's own data, for the Contract View tab: the contract read as
  prose, with the values Concerto filled in highlighted rather than left as
  bare {{fieldName}} tokens.

  This is not Accord's TemplateMark engine -- that's a JavaScript toolchain
  (see docs/walkthrough.md) with loops, sub-clauses, formulas, and its own
  formatting directives. What's implemented here is only what the one real
  template this app has (docs/examples/manager-employment-contract) actually
  uses: {{var}} substitution, {{#optional x}}...{{else}}...{{/optional}} and
  {{#if x}}...{{else}}...{{/if}} blocks, paragraph/heading/horizontal-rule
  block structure, and **bold** inline markup. A grammar leaning on anything
  past that (loops, nested clauses, computed values) will render those
  directives as literal text rather than silently dropping or mis-rendering
  them -- see render-value's fallback for an unresolved {{var}}.

  Where it does implement something, it follows TemplateMark's semantics
  rather than mustache's -- notably scope rebinding inside blocks, which is
  the difference that makes a grammar either draft or fail through Accord's
  own engine. See resolve-conditionals, and docs/templatemark-conformance.md
  for how those semantics were established."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------- tokenize

(def ^:private token-pattern
  #"\{\{(#if\s+[\w.]+|#optional\s+[\w.]+|else|/if|/optional|[\w.]+)\}\}")

(defn- tokenize
  "Raw grammar text -> a flat seq of [:text s] / [:var name] / [:if-start
  flag] / [:optional-start name] / [:else] / [:if-end] / [:optional-end]
  tokens, in source order."
  [text]
  (let [m (re-matcher token-pattern text)]
    (loop [pos 0, tokens (transient [])]
      (if (.find m)
        (let [start (.start m), end (.end m), inner (.group m 1)]
          (recur end
                 (cond-> tokens
                   (< pos start) (conj! [:text (subs text pos start)])
                   true          (conj! (cond
                                          (str/starts-with? inner "#if")
                                          [:if-start (str/trim (subs inner 3))]

                                          (str/starts-with? inner "#optional")
                                          [:optional-start (str/trim (subs inner 9))]

                                          (= inner "else")      [:else]
                                          (= inner "/if")       [:if-end]
                                          (= inner "/optional") [:optional-end]
                                          :else                 [:var inner])))))
        (persistent!
         (cond-> tokens
           (< pos (count text)) (conj! [:text (subs text pos)])))))))

;; ------------------------------------------------------------------ parse

(defn- parse-block
  "tokens -> [nodes remaining-tokens], stopping at a block terminator
  (:else/:if-end/:optional-end, left for the caller to consume) or end of
  input. Recurs into {{#if}} and {{#optional}} to build [:if flag then
  else] and [:optional name body] nodes."
  [tokens]
  (loop [tokens tokens, nodes (transient [])]
    (if (empty? tokens)
      [(persistent! nodes) tokens]
      (let [[kind :as tok] (first tokens), more (rest tokens)]
        (case kind
          (:text :var) (recur more (conj! nodes tok))
          :if-start
          (let [[then-nodes rest1] (parse-block more)
                at-else?           (= :else (first (first rest1)))
                [else-nodes rest2] (if at-else? (parse-block (rest rest1)) [[] rest1])
                rest3              (rest rest2)] ; drop the :if-end
            (recur rest3 (conj! nodes [:if (second tok) then-nodes else-nodes])))

          :optional-start
          (let [[body rest1]       (parse-block more)
                at-else?           (= :else (first (first rest1)))
                [else-nodes rest2] (if at-else? (parse-block (rest rest1)) [[] rest1])
                rest3              (rest rest2)] ; drop the :optional-end
            (recur rest3 (conj! nodes [:optional (second tok) body else-nodes])))

          (:else :if-end :optional-end) [(persistent! nodes) tokens])))))

;; --------------------------------------------------------------- resolve

(defn- resolve-conditionals
  "Parsed nodes -> a flat seq of resolved runs: [:text s], [:value v] for a
  variable that resolved, [:missing name] for one that didn't.

  `scope` is what bare names resolve against, and {{this}} is the scope
  itself. Both block forms rebind scope to the value they test, which is
  TemplateMark's own behaviour rather than mustache's -- verified against
  Accord's engine, where naming a sibling property inside either block is
  an 'Unknown property' error because scope has moved. Matching it here
  means a grammar that drafts through Accord renders the same way on this
  page, and one that doesn't shows dimmed unresolved tokens instead of
  quietly looking fine.

  {{#optional x}} drops its body entirely when x is absent and renders its
  {{else}} branch instead; {{#if x}} picks a branch on truthiness."
  [nodes scope]
  (mapcat (fn [[kind a b c]]
            (case kind
              :text [[:text a]]
              :var  (cond
                      (= a "this")                     [[:value scope]]
                      (and (map? scope)
                           (contains? scope a))        [[:value (get scope a)]]
                      :else                            [[:missing a]])
              :if   (let [v (get scope a)]
                      (if v
                        (resolve-conditionals b v)
                        (resolve-conditionals c scope)))
              :optional (let [v (get scope a)]
                          (if (some? v)
                            (resolve-conditionals b v)
                            (resolve-conditionals c scope)))))
          nodes))

;; ------------------------------------------------------------------ blocks

(defn- split-blocks
  "Resolved runs -> a seq of blocks (each a seq of runs), split on blank
  lines. Splitting only ever happens inside a :text run's own string, so a
  :var run never straddles a block boundary."
  [runs]
  (loop [runs runs, blocks [], current []]
    (if (empty? runs)
      (cond-> blocks (seq current) (conj current))
      (let [[kind v] (first runs), more (rest runs)]
        (if (= kind :text)
          ;; -1: str/split silently drops trailing (and never reports
          ;; leading) empty strings without it, which loses exactly the
          ;; boundary this is trying to detect -- a run ending right at a
          ;; blank line (e.g. a heading line followed by one) would then
          ;; look like "no split happened" and merge into the next block.
          (let [parts (str/split v #"\n[ \t]*\n+" -1)]
            (if (<= (count parts) 1)
              (recur more blocks (conj current [:text v]))
              (let [mid-parts (butlast (rest parts))
                    blocks'   (into (conj blocks (conj current [:text (first parts)]))
                                    (map (fn [p] [[:text p]]))
                                    mid-parts)]
                (recur more blocks' [[:text (last parts)]]))))
          (recur more blocks (conj current [kind v])))))))

(defn- block-blank? [block]
  (every? (fn [[kind v]] (and (= kind :text) (str/blank? v))) block))

(defn- classify
  "A block -> :hr, [:heading level text], or :paragraph. Headings and rules
  are recognized only as a block's sole, plain-text run -- true of every
  heading/rule in the one grammar this renders, and a safe default (falls
  through to :paragraph) for anything else."
  [block]
  (if (and (= 1 (count block)) (= :text (ffirst block)))
    (let [s (str/trim (second (first block)))]
      (cond
        (re-matches #"-{3,}" s)       :hr
        (re-matches #"#{1,6}\s.*" s)  [:heading (count (re-find #"^#+" s))
                                       (str/replace s #"^#+\s*" "")]
        :else                         :paragraph))
    :paragraph))

;; ------------------------------------------------------------------ inline

(defn- render-value
  "A resolved variable, highlighted. A name the data didn't have (a real
  gap, not a false/blank value) renders as the bare token, dimmed, rather
  than silently vanishing."
  [kind v]
  (if (= kind :value)
    [:span {:class ["text-blue-700 font-medium"]} (str v)]
    [:span {:class ["italic text-gray-400"]} (str "{{" v "}}")]))

(defn- render-inline
  "A block's resolved runs -> inline hiccup children, highlighting values
  and honouring **bold** -- the latter tracked as a single flag threaded
  across runs, since a bold span can (and in this grammar's signature
  block, does) wrap a variable rather than sit entirely inside one text
  run."
  [block]
  (:nodes
   (reduce
    (fn [{:keys [bold? nodes]} [kind v]]
      (if (not= kind :text)
        (let [span (render-value kind v)]
          {:bold? bold? :nodes (conj nodes (if bold? [:strong span] span))})
        (reduce
         (fn [{:keys [bold? nodes]} [i piece]]
           (let [bold? (if (pos? i) (not bold?) bold?)]
             {:bold? bold?
              :nodes (cond-> nodes (not (str/blank? piece))
                             (conj (if bold? [:strong piece] piece)))}))
         {:bold? bold? :nodes nodes}
         (map-indexed vector (str/split v #"\*\*" -1)))))
    {:bold? false :nodes []}
    block)))

(defn- render-block [block]
  (let [c (classify block)]
    (cond
      (= c :hr)        [:hr {:class ["my-4 border-gray-300"]}]
      (= c :paragraph) (into [:p {:class ["mb-4 leading-relaxed"]}] (render-inline block))
      :else            (let [[_ level text] c]
                         [(keyword (str "h" (min 4 (inc level))))
                          {:class ["font-semibold mt-6 mb-2"]} text]))))

;; -------------------------------------------------------------------- api

(defn render
  "A grammar.tem.md source string, rendered against a contract instance's
  data as a seq of hiccup blocks -- see the ns docstring for exactly what
  subset of TemplateMark this covers."
  [grammar data]
  (->> (tokenize grammar)
       parse-block
       first
       (#(resolve-conditionals % data))
       split-blocks
       (remove block-blank?)
       (mapv render-block)))
