(ns com.trustblocks.template.render
  "Renders a TemplateMark grammar (text/grammar.tem.md) against a contract
  instance's own data, for the Contract View tab: the contract read as
  prose, with the values Concerto filled in highlighted rather than left as
  bare {{fieldName}} tokens.

  This is not Accord's TemplateMark engine -- that's a JavaScript toolchain
  (see docs/walkthrough.md) with loops, sub-clauses, formulas, and its own
  formatting directives. What's implemented here is only what the one real
  template this app has (docs/examples/manager-employment-contract) actually
  uses: {{var}} substitution, {{#if flag}}...{{else}}...{{/if}} branching,
  paragraph/heading/horizontal-rule block structure, and **bold** inline
  markup. A grammar leaning on anything past that (loops, nested clauses,
  computed values) will render those directives as literal text rather than
  silently dropping or mis-rendering them -- see resolve/2's fallback for an
  unresolved {{var}}."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------- tokenize

(def ^:private token-pattern
  #"\{\{(#if\s+[\w.]+|else|/if|[\w.]+)\}\}")

(defn- tokenize
  "Raw grammar text -> a flat seq of [:text s] / [:var name] / [:if-start
  flag] / [:else] / [:if-end] tokens, in source order."
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
                                          (= inner "else") [:else]
                                          (= inner "/if")  [:if-end]
                                          :else            [:var inner])))))
        (persistent!
         (cond-> tokens
           (< pos (count text)) (conj! [:text (subs text pos)])))))))

;; ------------------------------------------------------------------ parse

(defn- parse-block
  "tokens -> [nodes remaining-tokens], stopping at :else/:if-end (left for
  the caller to consume) or end of input. Recurs into {{#if}} to build an
  [:if flag then-nodes else-nodes] node."
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
          (:else :if-end) [(persistent! nodes) tokens])))))

;; --------------------------------------------------------------- resolve

(defn- resolve-conditionals
  "Parsed nodes -> a flat seq of [:text s] / [:var name] runs, with each
  {{#if}} replaced by whichever branch `data` selects."
  [nodes data]
  (mapcat (fn [[kind a b c]]
            (case kind
              :text [[:text a]]
              :var  [[:var a]]
              :if   (resolve-conditionals (if (get data a) b c) data)))
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

(defn- render-var
  "A {{name}} reference, resolved against `data` and highlighted. A name
  data doesn't have (a real gap, not a false/blank value) renders as the
  bare token, dimmed, rather than silently vanishing."
  [name data]
  (if (contains? data name)
    [:span {:class ["text-blue-700 font-medium"]} (str (get data name))]
    [:span {:class ["italic text-gray-400"]} (str "{{" name "}}")]))

(defn- render-inline
  "A block's runs -> inline hiccup children, resolving {{var}} highlighting
  and **bold** -- the latter tracked as a single flag threaded across runs,
  since a bold span can (and in this grammar's signature block, does) wrap
  a {{var}} rather than sit entirely inside one text run."
  [block data]
  (:nodes
   (reduce
    (fn [{:keys [bold? nodes]} [kind v]]
      (if (= kind :var)
        (let [span (render-var v data)]
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

(defn- render-block [block data]
  (let [c (classify block)]
    (cond
      (= c :hr)        [:hr {:class ["my-4 border-gray-300"]}]
      (= c :paragraph) (into [:p {:class ["mb-4 leading-relaxed"]}] (render-inline block data))
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
       (mapv #(render-block % data))))
