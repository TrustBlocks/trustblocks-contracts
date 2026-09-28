(ns com.trustblocks.lib.code
  "Source listings: numbered lines, each rendered on its own with its
  whitespace kept exactly -- so a model's indentation survives whatever
  styles surround it -- and Concerto (.cto), EDN and JSON syntax
  highlighted.

  The highlighter is a small line tokenizer, not a parser: it colours what
  a reader scans for -- keywords, the `o` and `-->` of each property, type
  names, @decorators, strings, numbers, and comments, including /** */
  blocks that span lines. Anything it does not recognise is shown as
  written, so a mistake in it costs colour, never text."
  (:require [clojure.string :as str]))

(def ^:private cto-keywords
  #{"namespace" "import" "from" "concept" "asset" "participant" "transaction"
    "event" "enum" "scalar" "map" "abstract" "extends" "identified" "by"
    "optional" "default" "regex" "range" "length" "o" "-->"})

(def ^:private token-classes
  {:comment   ["text-emerald-300" "italic"]
   :keyword   ["text-sky-300" "font-semibold"]
   :type      ["text-amber-200"]
   :decorator ["text-fuchsia-300"]
   :string    ["text-orange-300"]
   :number    ["text-violet-300"]
   :text      ["text-slate-100"]})

(def ^:private token-re
  "One token at a time: a line comment, the start of a block comment,
  a string, a decorator, the relationship arrow, a word, a number, or any
  other single character."
  #"//.*|/\*.*|\"(?:[^\"\\]|\\.)*\"|@\w+|-->|[A-Za-z_$][\w$]*|-?\d+(?:\.\d+)?|\s+|.")

(defn- classify [tok]
  (cond
    (str/starts-with? tok "//")             :comment
    (str/starts-with? tok "\"")             :string
    (str/starts-with? tok "@")              :decorator
    (cto-keywords tok)                      :keyword
    (re-matches #"-?\d+(?:\.\d+)?" tok)      :number
    (re-matches #"[A-Z][\w$]*" tok)          :type
    :else                                   :text))

(defn- tokens
  "The tokens of one line, as [kind text], given whether it starts inside
  a /* */ block; returns [tokens still-in-block?]."
  [line in-block?]
  (loop [rest-of line, in-block? in-block?, out []]
    (cond
      (str/blank? rest-of) [(cond-> out (seq rest-of) (conj [:text rest-of])) in-block?]

      in-block?
      (if-let [end (str/index-of rest-of "*/")]
        (recur (subs rest-of (+ end 2)) false (conj out [:comment (subs rest-of 0 (+ end 2))]))
        [(conj out [:comment rest-of]) true])

      :else
      (let [tok (re-find token-re rest-of)]
        (if (str/starts-with? tok "/*")
          (recur rest-of true out)
          (recur (subs rest-of (count tok)) false (conj out [(classify tok) tok])))))))

(defn- indentation
  "A line's leading spaces, drawn so a reader sees them: each two-space
  level twice as wide, with a faint guide at its left edge, as editors
  draw indentation. The spaces themselves are kept, so copied text is the
  source exactly."
  [spaces]
  (let [levels (quot (count spaces) 2)
        extra  (subs spaces (* 2 levels))]
    (concat (repeat levels [:span {:class ["inline-block w-[4ch] border-l border-slate-700"]} "  "])
            (when (seq extra) [[:span extra]]))))

(defn- line-row [n content]
  [:div {:id (str "L" n) :class ["flex"]}
   [:span {:class ["w-12 shrink-0 select-none pr-4 text-right text-slate-500"]} n]
   ;; white-space: pre on the line itself, not only on a surrounding <pre>.
   [:span {:class ["whitespace-pre"]} content]])

(defn cto-listing
  "A Concerto source, numbered and highlighted."
  [source]
  [:div {:class ["overflow-auto rounded-lg bg-slate-950 py-4 font-mono text-xs leading-6"]}
   (second
    (reduce (fn [[in-block? rows] [i line]]
              (let [lead (re-find #"^ *" line)
                    [toks in-block?'] (tokens (subs line (count lead)) in-block?)]
                [in-block?'
                 (conj rows (line-row (inc i)
                                      (if (and (empty? toks) (empty? lead))
                                        " "
                                        (concat (indentation lead)
                                                (for [[kind text] toks]
                                                  [:span {:class (token-classes kind)} text])))))]))
            [false []]
            (map-indexed vector (str/split-lines (or source "")))))])

;; ------------------------------------------------------------------ data

(def ^:private data-token-re
  "EDN or JSON, one token at a time: a ; comment, a string, a :keyword, a
  number, a word, whitespace, or any other single character."
  #";.*|\"(?:[^\"\\]|\\.)*\"|:[\w.*+!?/<>=$-]+|-?\d+(?:\.\d+)?|[A-Za-z_][\w-]*|\s+|.")

(defn- data-classify [tok next-tok]
  (cond
    (str/starts-with? tok ";")               :comment
    ;; A JSON key is a string followed, past any spaces, by a colon.
    (and (str/starts-with? tok "\"") (= ":" next-tok)) :keyword
    (str/starts-with? tok "\"")              :string
    (str/starts-with? tok ":")               :keyword
    (re-matches #"-?\d+(?:\.\d+)?" tok)      :number
    (#{"true" "false" "nil" "null"} tok)     :decorator
    :else                                    :text))

(defn data-listing
  "An EDN or JSON source, numbered and highlighted like cto-listing: keys,
  strings, numbers, literals and ; comments."
  [source]
  [:div {:class ["overflow-auto rounded-lg bg-slate-950 py-4 font-mono text-xs leading-6"]}
   (for [[i line] (map-indexed vector (str/split-lines (or source "")))
         :let [lead (re-find #"^ *" line)
               toks (re-seq data-token-re (subs line (count lead)))]]
     (line-row (inc i)
               (if (and (empty? toks) (empty? lead))
                 " "
                 (concat (indentation lead)
                         (for [[tok more] (map vector toks (iterate rest (rest toks)))
                               :let [next-tok (first (remove str/blank? more))]]
                           [:span {:class (token-classes (data-classify tok next-tok))} tok])))))])
