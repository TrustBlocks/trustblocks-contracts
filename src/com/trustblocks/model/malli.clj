(ns com.trustblocks.model.malli
  "One contract type's model, as Malli -- what the contract model page's
  Malli tab shows: the Concerto model compiled to a native Malli schema
  (plain EDN, com.trustblocks.concerto.malli), and what that one piece of
  data then does on its own:

    validate   a stored contract, with Malli's humanized errors
    generate   a valid contract, made up from the schema alone
    describe   the same model as JSON Schema -- Malli's export, not
               Concerto's
    draw       the types and how they refer to each other, as Graphviz DOT

  Keys are Concerto's own property names as keywords (:contractId, :$class),
  concerto-clj's default -- the model as a reader knows it, not the
  kebab-cased keys XTDB stores (com.trustblocks.xtdb.store/schema). A
  stored contract is Concerto JSON, so it is keywordized to be checked.

  Two of Concerto's types are named schemas of concerto-clj's own --
  :concerto/date-time, an ISO-8601 string checked against a pattern, and
  :concerto/double, any JSON number -- so Malli has nothing to generate
  either from and no JSON Schema to give them. This namespace supplies
  both, as properties on the same schemas, and uses them only here:
  validation everywhere else is unchanged."
  (:require [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [clojure.walk :as walk]
            [com.trustblocks.concerto.malli :as cm]
            [malli.core :as m]
            [malli.dot :as md]
            [malli.error :as me]
            [malli.generator :as mg]
            [malli.json-schema :as json-schema]))

(def ^:private date-time-gen
  "Instants in the years around the contracts on file, written as Concerto
  writes them: 2026-07-24T15:00:00Z."
  (gen/fmap #(str (java.time.Instant/ofEpochSecond %))
            (gen/choose 1735689600 1861920000)))

(def ^:private amount-gen
  "Amounts to the cent, the way a contract's figures are written."
  (gen/fmap #(/ (Math/round (* % 100.0)) 100.0)
            (gen/double* {:min 0.0 :max 250000.0 :NaN? false :infinite? false})))

(def ^:private registry
  "concerto-clj's registry, with its two named types given a generator and a
  JSON Schema -- the same checks, wrapped."
  (merge cm/registry*
         {:concerto/date-time [:and {:gen/gen date-time-gen
                                     :json-schema {:type "string" :format "date-time"}}
                               cm/date-time]
          :concerto/double    [:and {:gen/gen amount-gen
                                     :json-schema {:type "number"}}
                               cm/double-like]}))

(defn- generate-from-regex
  "concerto-clj writes a regex-constrained String as [:and :string [:re ...]],
  and Malli generates an :and from its first child and filters -- any
  string, hoping to match. This points the generator at the regex itself."
  [form]
  (walk/postwalk
   (fn [x]
     (if-let [re (and (vector? x) (= :and (first x))
                      (some #(when (and (vector? %) (= :re (first %))) %) (rest x)))]
       (let [[props children] (if (map? (second x)) [(second x) (drop 2 x)] [{} (rest x)])]
         (into [:and (assoc props :gen/schema re)] children))
       x))
   form))

(defn schema
  "The Malli schema for contract type `fqn`, compiled from its Concerto
  model in `reg` -- with generators pointed where Malli cannot find them
  itself (see generate-from-regex and `registry`)."
  [reg fqn]
  (m/schema (generate-from-regex (m/form (cm/->schema reg fqn))) {:registry registry}))

(defn form
  "The schema as the EDN it is -- the native Malli form."
  [reg fqn]
  (m/form (cm/->schema reg fqn)))

(defn explain
  "Why `instance` (Concerto JSON) does not conform to `schema`, humanized,
  or nil when it does."
  [schema instance]
  (some-> (m/explain schema (walk/keywordize-keys instance)) me/humanize))

(defn sample
  "A valid instance made up from `schema` alone -- the same one for the
  same `seed`."
  [schema seed]
  (mg/generate schema {:seed seed :size 4}))

(defn json-schema
  "The model as JSON Schema, by Malli."
  [schema]
  (json-schema/transform schema))

(defn- plain-types
  "The model with concerto-clj's two named types written as Malli's own --
  for malli.dot, which re-reads a schema without the options that would
  resolve them. A picture of the structure; validation is unchanged."
  [form]
  (walk/postwalk #(case %
                    :concerto/date-time [:string {:format "date-time"}]
                    :concerto/double    :double
                    %)
                 form))

(defn- named-root
  "malli.dot draws a registry of named types when the root names one of
  them -- [:schema {:registry ...} \"Root\"], as its own examples do.
  concerto-clj ends in [:ref \"Root\"], which malli.dot takes for one
  anonymous type and draws as a single box holding the whole schema."
  [fqn form]
  (cond
    (and (vector? form) (= :schema (first form)) (vector? (last form)) (= :ref (first (last form))))
    (conj (pop form) (second (last form)))

    ;; A model with nothing nested is a bare :map; give it a registry of one,
    ;; so its box is named for its type too.
    (and (vector? form) (= :map (first form)))
    [:schema {:registry {fqn form}} fqn]

    :else form))

(defn- short-names
  "The diagram's labels: each type by its own name (PayItemLine, not its
  namespace), without the $class and $identifier entries every Concerto
  object carries -- bookkeeping, not the model -- and a long enum
  abbreviated."
  [form]
  (let [names (set (keys (get-in form [1 :registry])))
        short #(subs % (inc (str/last-index-of % ".")))]
    (walk/postwalk
     (fn [x]
       (cond
         (and (string? x) (names x)) (short x)

         (and (vector? x) (= :map (first x)))
         (into [] (remove #(and (vector? %) (#{:$class :$identifier} (first %)))) x)

         ;; A long enum -- the 50 USStates -- as its first few and a count,
         ;; so one line does not set the width of the whole box.
         (and (vector? x) (= :enum (first x)) (< 7 (count (rest x))))
         (let [[props values] (if (map? (second x)) [[(second x)] (drop 2 x)] [[] (rest x)])]
           (into [:enum] (concat props (take 4 values) [(str "… " (- (count values) 4) " more")])))

         :else x))
     form)))

(defn dot
  "The model's types and their references, as Graphviz DOT, by Malli."
  [reg fqn]
  (md/transform (short-names (named-root fqn (plain-types (m/form (cm/->schema reg fqn)))))))
