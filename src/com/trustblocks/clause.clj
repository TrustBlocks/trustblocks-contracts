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

(defn- duration-days
  "An org.accordproject.time Duration ({\"amount\" n, \"unit\" u}) -> days, as
  a double. The same unit table Accord's own TypeScript clause logic converts
  through milliseconds; converting through days instead keeps every clause
  computation on the axis `days-between` already returns."
  [d]
  (let [amount (get d "amount")
        unit   (get d "unit")]
    (case unit
      "seconds" (/ amount 86400.0)
      "minutes" (/ amount 1440.0)
      "hours"   (/ amount 24.0)
      "days"    (double amount)
      "weeks"   (* amount 7.0)
      (throw (ex-info "Unsupported duration unit" {:unit unit})))))

;; ---------------------------------------------------------------- vocabulary

(def special-forms
  "Special forms the permitted macros expand into.

  `let` becomes `let*`, `fn` becomes `fn*`, and `cond`, `when` and the threading
  macros become `if` and `do`. Allowing a macro without its expansion target
  refuses the macro at the point of use, so these have to be named too.

  `throw` lets a clause refuse to execute -- a certification that cannot be
  granted yet, an authorization outside its window -- rather than every
  clause being forced to return some default response for a request it
  should reject outright."
  '[if do fn fn* let* quote recur throw])

(def core-vocabulary
  "The clojure.core names a clause may use.

  Listed rather than derived, so the surface is auditable by reading it. Note
  what is not here: `/`, `rand`, `eval`, `require`, `def`, `atom`, anything that
  reads a clock, anything that touches a file, socket or the JVM.

  `ex-info`/`ex-message` pair with `throw` above -- constructing and reading
  back a data-carrying refusal, never anything that inspects the JVM."
  '[+ - * < > <= >= = not= not
    min max mod rem quot inc dec
    when when-not cond and or let if-let when-let
    get get-in contains? keys vals
    count empty? seq first second last nth
    map filter remove reduce some every? sort-by
    odd? even? pos? neg? zero?
    assoc dissoc merge select-keys update
    str name keyword vector hash-map list conj into apply
    nil? some? true? false? string? number? boolean? map? vector?
    ex-info ex-message
    -> ->>])

(def contract-vocabulary
  "Host functions a clause may call, beyond clojure.core.

  Every date operation takes and returns ISO-8601 strings, so a clause never
  holds a platform date object -- the same reason the schemas validate the wire
  form rather than a parsed value."
  {'divide        divide
   'plus-days     plus-days
   'days-between  days-between
   'before?       before?
   'after?        after?
   'duration-days duration-days})

(defn context
  "An SCI context holding exactly the vocabulary and nothing else.

  `:allow` is the whitelist, and it is a real one: SCI refuses any symbol not
  named, including special forms and including reached-for spellings like
  `(apply / ...)` and `clojure.core//`. `:namespaces` supplies the host
  functions; it does *not* restrict anything, which is the mistake an earlier
  version of this made -- treating it as the whitelist left `/`, `rand`,
  `rand-int` and `atom` reachable, patched afterwards with a `:deny` list. A
  whitelist backed by a blacklist is weaker than one that stands alone, and
  this one now stands alone.

  `extra` adds host functions for a deployment that needs them; adding one
  widens what every clause can do, so it should be rare and reviewed."
  [& {:keys [extra]}]
  (sci/init
   {:namespaces {'clojure.core (merge contract-vocabulary extra)}
    :allow      (concat special-forms core-vocabulary
                        (keys contract-vocabulary) (keys extra))
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
