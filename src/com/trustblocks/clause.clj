(ns com.trustblocks.clause
  "Contract clause logic, evaluated under an explicitly configured vocabulary.

  Accord's own clause logic is TypeScript, and the archive's `package.json`
  declares `\"runtime\": \"typescript\"` -- a field that has already held another
  value, `\"ergo\"`, in published templates. So the logic runtime is pluggable by
  design, and this is a third one.

  The point is not the language. Ergo was trustworthy because it was
  *restricted*: it could not reach the network, import arbitrary code, or read a
  clock. General-purpose Clojure has strictly more ambient authority than
  sandboxed TypeScript, so it would be a step backwards on its own. What makes
  this defensible is that the host decides what exists -- the vocabulary below
  is the entire surface a clause can reach.

  Two absences are deliberate and load-bearing:

  no clock    Accord's own acceptance-of-delivery clause calls `new Date()`, so
              re-running the same request tomorrow gives a different answer. It
              cannot be replayed, which means the stored response is the only
              record of what was decided. Here time arrives as an argument, so a
              clause is a pure function of its inputs and can be re-evaluated.

  no `/`      `(/ 1 3)` is an exact ratio on the JVM and a float in
              ClojureScript. Contract logic computing interest or proration
              would silently diverge between a server and a browser running
              identical source. `divide` takes an explicit scale instead."
  (:require [sci.core :as sci])
  (:import [java.math RoundingMode]
           [java.time OffsetDateTime]
           [java.time.format DateTimeFormatter]
           [java.time.temporal ChronoUnit]))

;; ---------------------------------------------------------------- host fns

(defn- parse-dt
  "ISO-8601 string -> OffsetDateTime. Dates arrive and leave as their wire form;
  this is the only place they become platform values, and they never escape as
  such."
  ^OffsetDateTime [s]
  (when s
    (let [s (str s)]
      (try (OffsetDateTime/parse s)
           (catch Exception _
             ;; A Concerto DateTime may omit the zone; read it as UTC.
             (OffsetDateTime/parse (cond-> s
                                     (not (re-find #"[T ]" s)) (str "T00:00:00")
                                     true                      (str "Z"))))))))

(defn- fmt-dt ^String [^OffsetDateTime d]
  (.format d DateTimeFormatter/ISO_OFFSET_DATE_TIME))

(defn- divide
  "Decimal division to an explicit number of places, half-up.

  Not `/`. The scale has to be stated because the answer depends on it, and
  stating it is what makes the result the same everywhere."
  [a b scale]
  (when (zero? (bigdec b))
    (throw (ex-info "Division by zero in clause logic" {:numerator a})))
  (double (.setScale (.divide (bigdec a) (bigdec b) ^int (int scale) RoundingMode/HALF_UP)
                     ^int (int scale) RoundingMode/HALF_UP)))

(defn- plus-days [dt n]
  (fmt-dt (.plusDays (parse-dt dt) (long n))))

(defn- days-between [a b]
  (.between ChronoUnit/DAYS (parse-dt a) (parse-dt b)))

(defn- before? [a b] (.isBefore (parse-dt a) (parse-dt b)))
(defn- after?  [a b] (.isAfter  (parse-dt a) (parse-dt b)))

;; ---------------------------------------------------------------- vocabulary

(def core-vocabulary
  "The clojure.core names a clause may use.

  Listed rather than derived, so the surface is auditable by reading it. Note
  what is not here: `/`, `rand`, `eval`, `require`, anything that reads a clock,
  anything that touches a file, socket or the JVM."
  '[+ - * < > <= >= = not= not
    min max mod rem quot inc dec abs
    if when when-not cond and or let if-let when-let
    get get-in contains? keys vals
    count empty? seq first second last nth
    map filter remove reduce some every? sort-by
    assoc dissoc merge select-keys update
    str name keyword vector hash-map list conj into
    nil? some? true? false? string? number? boolean? map? vector?
    -> ->>])

(def contract-vocabulary
  "Host functions a clause may call, beyond clojure.core.

  Every date operation takes and returns ISO-8601 strings, so a clause never
  holds a platform date object -- the same reason the schemas validate the wire
  form rather than a parsed value."
  {'divide       divide
   'plus-days    plus-days
   'days-between days-between
   'before?      before?
   'after?       after?})

(defn context
  "An SCI context holding exactly the vocabulary and nothing else.

  `extra` adds host functions for a deployment that needs them; adding one is a
  deliberate widening of what every clause can do, so it should be rare and
  reviewed."
  [& {:keys [extra]}]
  (sci/init
   {:namespaces {'clojure.core (merge (select-keys (ns-publics 'clojure.core) core-vocabulary)
                                      contract-vocabulary
                                      extra)}
    ;; SCI provides some clojure.core bindings regardless of :namespaces, so the
    ;; whitelist above is not by itself the whole surface. These have to be
    ;; denied explicitly, and they are the ones that would silently break a
    ;; contract rather than obviously break it:
    ;;
    ;;   /            exact ratio on the JVM, float in ClojureScript
    ;;   rand         a clause that is not a function of its inputs
    ;;   atom & co.   mutable state surviving across an evaluation
    ;;
    ;; Belt and braces. A whitelist that has to be backed by a blacklist is
    ;; weaker than one that does not, and this list should be re-audited
    ;; whenever SCI is upgraded.
    :deny       '[eval require import ns in-ns def defn defmacro set! new .
                  / rand rand-int shuffle random-uuid
                  atom swap! reset! swap-vals! reset-vals! compare-and-set!
                  ref alter ref-set dosync agent send send-off
                  future promise deliver delay force
                  read read-string load-string load-file with-open
                  volatile! vswap! vreset!]
    :classes    {}}))


;; ---------------------------------------------------------------- analysis

(defn check
  "Compile a clause without running it. Returns nil, or why it was refused.

  SCI resolves symbols when it builds the function, so a clause reaching for
  anything outside the vocabulary fails here -- including from inside a nested
  `fn` -- with none of the clause body executing. That makes this the sandbox
  itself answering rather than a guess about it.

  An earlier version walked the source comparing symbols against a whitelist and
  flagged every `let`-bound name as unknown. Reading the form is the wrong tool:
  deciding what is in scope means reimplementing the analyzer that SCI already
  has.

  One consequence worth knowing: map destructuring is unavailable to clauses,
  because it compiles to a host class call that the vocabulary does not expose.
  That costs almost nothing here -- Concerto instances are string-keyed, so
  `{:keys [...]}` would bind nothing anyway. Use `get` and `get-in`."
  [ctx src]
  (try (sci/eval-string* ctx (str "(fn [data request state now] " src ")"))
       nil
       (catch Exception e (ex-message e))))

;; ---------------------------------------------------------------- evaluating

(defn evaluate
  "Run a clause.

  `src` is the clause body as text, evaluated with `data`, `request`, `state`
  and `now` in scope. It returns a map with :response, and optionally :state and
  :obligations -- the same shape Accord's runtime expects, minus the injected
  base class.

  Instances are string-keyed Concerto JSON on the way in and out, matching what
  `com.trustblocks.xtdb.store` reads and writes."
  [ctx src {:keys [data request state now]}]
  (let [f (sci/eval-string* ctx (str "(fn [data request state now] " src ")"))]
    (f data request state now)))
