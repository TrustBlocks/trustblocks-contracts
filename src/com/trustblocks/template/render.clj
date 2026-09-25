(ns com.trustblocks.template.render
  "Renders a TemplateMark grammar (text/grammar.tem.md) against a contract
  instance's own data, for the Contract View tab: the contract read as
  prose, with the values Concerto filled in highlighted rather than left as
  bare {{fieldName}} tokens.

  This is not Accord's TemplateMark engine -- that is a JavaScript
  toolchain, and trustblocks-templates' bb conformance is where a template
  is held to it. What this implements is the part of TemplateMark our
  templates use, in two layers:

  - The template layer, here: {{var}} substitution, {{var as \"...\"}}
    formatting (amount patterns such as 0,0.00, and the documented DateTime
    tokens), {{#optional x}}...{{else}}...{{/optional}},
    {{#if x}}...{{else}}...{{/if}}, and {{#ulist x}} / {{#olist x}} over an
    array. Where it implements something it follows TemplateMark's semantics
    rather than mustache's -- notably that every block rebinds scope to the
    value it tests or iterates, which is the difference between a grammar
    that drafts through Accord's engine and one that does not. See
    resolve-nodes.

  - The markdown layer: TemplateMark is CommonMark plus pipe tables, and
    that is exactly what renders the result -- commonmark-java with its
    tables extension -- rather than a hand-rolled subset. Raw HTML in a
    grammar is escaped, not passed through, so a template cannot put markup
    on the page.

  A resolved value never passes through the markdown parser. Each becomes a
  placeholder of private-use characters that CommonMark treats as plain
  text, and is swapped for its HTML-escaped, highlighted span only after
  rendering -- so a value containing *, _ or <tag> reads as those
  characters, and a value can never change the document's structure.

  A name the data does not have renders as the bare {{token}}, dimmed,
  rather than silently vanishing; so does a directive this does not
  implement (formulas, {{#with}}, {{#join}}, {{#clause}}), as literal text."
  (:require [clojure.string :as str]
            [dev.onionpancakes.chassis.core :as chassis])
  (:import [java.math RoundingMode]
           [java.time OffsetDateTime]
           [java.time.format DateTimeFormatter]
           [java.util Locale]
           [org.commonmark.ext.gfm.tables TablesExtension]
           [org.commonmark.parser Parser]
           [org.commonmark.renderer.html HtmlRenderer]))

;; ---------------------------------------------------------------- tokenize

(def ^:private token-pattern
  #"\{\{(#(?:if|optional|ulist|olist)\s+[\w.]+|else|/(?:if|optional|ulist|olist)|[\w.]+(?:\s+as\s+\"[^\"]*\")?)\}\}")

(defn- var-token [inner]
  (if-let [[_ nm fmt] (re-matches #"([\w.]+)\s+as\s+\"([^\"]*)\"" inner)]
    [:var nm fmt]
    [:var inner nil]))

(defn- tokenize
  "Raw grammar text -> a flat seq of tokens in source order: [:text s],
  [:var name format-or-nil], [:open kind name], [:else], [:close kind],
  kind being :if, :optional, :ulist or :olist."
  [text]
  (let [m (re-matcher token-pattern text)]
    (loop [pos 0, tokens (transient [])]
      (if (.find m)
        (let [start (.start m), end (.end m), inner (.group m 1)]
          (recur end
                 (cond-> tokens
                   (< pos start) (conj! [:text (subs text pos start)])
                   true          (conj! (cond
                                          (= inner "else") [:else]

                                          (str/starts-with? inner "#")
                                          (let [[kind nm] (str/split (subs inner 1) #"\s+" 2)]
                                            [:open (keyword kind) nm])

                                          (str/starts-with? inner "/")
                                          [:close (keyword (subs inner 1))]

                                          :else (var-token inner))))))
        (persistent!
         (cond-> tokens
           (< pos (count text)) (conj! [:text (subs text pos)])))))))

;; ------------------------------------------------------------------ parse

(defn- literal
  "A directive token restored to its source text, for rendering literally."
  [[kind k]]
  [:text (case kind :else "{{else}}" :close (str "{{/" (name k) "}}"))])

(defn- parse-nodes
  "tokens -> [nodes remaining-tokens]. `open` is the kind of block being
  parsed (nil at the top level): parsing stops at that block's own
  {{/kind}}, or at {{else}} inside #if/#optional, leaving it for the
  caller. A block becomes [:block kind name body else-body].

  Any other {{else}} or {{/kind}} -- stray at the top level, or closing a
  block other than the open one -- is rendered as literal text rather than
  silently ending the document there."
  ([tokens] (parse-nodes tokens nil))
  ([tokens open]
   (loop [tokens tokens, nodes (transient [])]
     (if (empty? tokens)
       [(persistent! nodes) tokens]
       (let [[kind k :as tok] (first tokens), more (rest tokens)]
         (case kind
           (:text :var) (recur more (conj! nodes tok))
           :open (let [[_ block-kind nm]  tok
                       [body rest1]       (parse-nodes more block-kind)
                       at-else?           (= :else (ffirst rest1))
                       [else-body rest2]  (if at-else? (parse-nodes (rest rest1) block-kind) [[] rest1])]
                   (recur (rest rest2) ; drop the :close
                          (conj! nodes [:block block-kind nm body else-body])))
           :else (if (#{:if :optional} open)
                   [(persistent! nodes) tokens]
                   (recur more (conj! nodes (literal tok))))
           :close (if (= k open)
                    [(persistent! nodes) tokens]
                    (recur more (conj! nodes (literal tok))))))))))

;; ----------------------------------------------------------------- format

(def ^:private currency-symbols
  {"USD" "$" "GBP" "£" "EUR" "€" "JPY" "¥" "CAD" "$" "AUD" "$"})

(defn- amount
  "`n` formatted by a TemplateMark amount pattern -- 0,0 or 0 0 for the
  thousands separator, then optionally a decimal separator and the number
  of places, e.g. 0,0.00 -> 12,000.00 -- with `currency` (an ISO code, or
  nil) placed where the pattern asks: K before the number as a symbol, CCC
  after it as the code. nil if `fmt` is not an amount pattern."
  [n fmt currency]
  (when-let [[_ k group _ dec places ccc] (re-matches #"(K)?0([ ,])0(([.,])(0+))?( ?CCC)?" fmt)]
    (let [scale (count (or places ""))
          bd    (.setScale (bigdec n) scale RoundingMode/HALF_UP)
          [i f] (str/split (.toPlainString (.abs bd)) #"\." 2)
          int*  (->> (reverse i) (partition-all 3) (map (comp str/join reverse)) reverse
                     (str/join group))]
      (str (when (neg? (.signum bd)) "-")
           (when (and k currency) (get currency-symbols currency currency))
           int* (when f (str dec f))
           (when (and ccc currency) (str (when (str/starts-with? ccc " ") " ") currency))))))

(def ^:private date-token
  #"YYYY|MMMM|MMM|MM|M|DD|D|HH|H|hh|h|mm|ss|SSS|Z|a|A|[^A-Za-z]+|[A-Za-z]")

(defn- java-date-pattern
  "A TemplateMark DateTime format as a java.time pattern, or nil if it uses
  a token TemplateMark does not document."
  [fmt]
  (let [toks (re-seq date-token fmt)
        java {"YYYY" "yyyy" "DD" "dd" "D" "d" "A" "a" "Z" "xxx"}]
    (when (every? #(or (re-matches #"[^A-Za-z]+" %)
                       (#{"YYYY" "MMMM" "MMM" "MM" "M" "DD" "D" "HH" "H" "hh" "h"
                          "mm" "ss" "SSS" "Z" "a" "A"} %))
                  toks)
      (str/join (for [t toks]
                  (if (re-matches #"[^A-Za-z]+" t)
                    (str "'" (str/replace t "'" "''") "'")
                    (get java t t)))))))

(defn- date
  "`s`, a Concerto DateTime string, formatted at its own UTC offset -- the
  offset the value was written with, as Accord's engine does -- or at UTC
  when it has none. nil if `s` is not a date-time or `fmt` is not a
  TemplateMark date format."
  [s fmt]
  (when-let [pattern (java-date-pattern fmt)]
    (when-let [dt (or (try (OffsetDateTime/parse s) (catch Exception _ nil))
                      ;; No offset: UTC, the same reading
                      ;; com.trustblocks.xtdb.execute/->instant gives it.
                      (try (OffsetDateTime/parse (str s "Z")) (catch Exception _ nil)))]
      (.format dt (DateTimeFormatter/ofPattern pattern Locale/US)))))

(defn- format-value
  "A value as the text shows it. Without a format a value renders as its
  own string -- DateTimes in their wire form, where Accord would print
  MM/DD/YYYY; a known divergence. A format this cannot apply renders the
  plain value rather than failing the page."
  [v fmt]
  (or (when fmt
        (cond
          (number? v) (amount v fmt nil)
          ;; org.accordproject.money MonetaryAmount
          (and (map? v) (number? (get v "doubleValue")))
          (amount (get v "doubleValue") fmt (get v "currencyCode"))

          (string? v) (date v fmt)))
      (str v)))

;; ---------------------------------------------------------------- resolve

(defn- placeholder [i] (str "\uE000" i "\uE001"))

(defn- resolve-nodes
  "Parsed nodes against `scope` -> markdown text, accumulating each resolved
  value in the `values` volatile and leaving a placeholder in its place.

  `scope` is what bare names resolve against, and {{this}} is the scope
  itself. Every block rebinds scope to the value it tests or iterates,
  which is TemplateMark's own behaviour rather than mustache's -- verified
  against Accord's engine, where naming a sibling property inside a block
  is an 'Unknown property' error because scope has moved. Matching it here
  means a grammar that drafts through Accord renders the same way on this
  page, and one that doesn't shows dimmed unresolved tokens instead of
  quietly looking fine.

  {{#optional x}} renders its body when x is present, else its {{else}}
  branch; {{#if x}} picks a branch on truthiness; {{#ulist x}} and
  {{#olist x}} render the body once per element of x, as the items of a
  CommonMark list."
  [nodes scope values]
  (let [emit (fn [v] (let [i (count (vswap! values conj v))] (placeholder (dec i))))]
    (str/join
     (for [[kind a b c d] nodes]
       (case kind
         :text a
         :var (cond
                (= a "this")                          (emit [:value (format-value scope b)])
                (and (map? scope) (contains? scope a)) (emit [:value (format-value (get scope a) b)])
                :else                                 (emit [:missing (str a (when b (str " as \"" b "\"")))]))
         :block
         (let [v (when (map? scope) (get scope b))]
           (case a
             :if       (if v (resolve-nodes c v values) (resolve-nodes d scope values))
             :optional (if (some? v) (resolve-nodes c v values) (resolve-nodes d scope values))
             (:ulist :olist)
             (let [items (for [item (if (sequential? v) v [])]
                           (str/replace (str/trim (resolve-nodes c item values)) #"\s*\n\s*" " "))]
               (if (empty? items)
                 ""
                 (str "\n\n"
                      (str/join "\n" (map-indexed (fn [i item]
                                                    (str (if (= a :olist) (str (inc i) ". ") "- ") item))
                                                  items))
                      "\n\n"))))))))))

;; ----------------------------------------------------------------- markdown

(def ^:private extensions [(TablesExtension/create)])

(def ^:private parser (-> (Parser/builder) (.extensions extensions) (.build)))

(def ^:private html-renderer
  ;; escapeHtml: raw HTML in a grammar is shown as text, never rendered.
  ;; sanitizeUrls: only http(s), mailto and data:image links survive, so no
  ;; javascript: link can reach the page.
  (-> (HtmlRenderer/builder) (.extensions extensions) (.escapeHtml true) (.sanitizeUrls true) (.build)))

(defn- escape-html [s]
  (-> (str s)
      (str/replace "&" "&amp;") (str/replace "<" "&lt;")
      (str/replace ">" "&gt;") (str/replace "\"" "&quot;")))

(defn- value-span [[kind v]]
  (if (= kind :value)
    (str "<span class=\"text-blue-700 font-medium\">" (escape-html v) "</span>")
    (str "<span class=\"italic text-gray-400\">{{" (escape-html v) "}}</span>")))

;; -------------------------------------------------------------------- api

(defn render-html
  "A grammar.tem.md source string rendered against a contract instance's
  data, as an HTML string -- see the ns docstring for what it covers."
  [grammar data]
  (let [values (volatile! [])
        md     (-> (tokenize grammar) parse-nodes first (resolve-nodes data values))
        html   (.render html-renderer (.parse parser md))]
    (-> html
        ;; Inside a tag -- a link's href, an image's alt -- a value can only
        ;; be escaped text: a span there would end the attribute.
        (str/replace #"<[^>]*>"
                     (fn [tag]
                       (str/replace tag #"\uE000(\d+)\uE001"
                                    (fn [[_ i]] (let [[kind v] (nth @values (parse-long i))]
                                                  (escape-html (if (= kind :value) v (str "{{" v "}}"))))))))
        ;; Everywhere else, the highlighted span.
        (str/replace #"\uE000(\d+)\uE001"
                     (fn [[_ i]] (value-span (nth @values (parse-long i))))))))

(defn render
  "render-html as hiccup children, for a page to place inside its own
  container -- the prose styles come from that container's contract-prose
  class (resources/tailwind.css)."
  [grammar data]
  [[:div {:class ["contract-prose"]} (chassis/raw-string (render-html grammar data))]])
