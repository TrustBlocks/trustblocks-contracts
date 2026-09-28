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
  ;; ClojureScript has a divide of its own; this one is the vocabulary's.
  (:refer-clojure :exclude [divide])
  (:require [sci.core :as sci])
  #?(:clj (:import [java.math BigDecimal RoundingMode]
                   [java.time OffsetDateTime]
                   [java.time.format DateTimeFormatter]
                   [java.time.temporal ChronoUnit])))

;; ---------------------------------------------------------------- host fns
;;
;; The same vocabulary runs on the JVM (the app, trigger!) and in the
;; browser (the Template Playground's logic worker), so a clause gives the
;; same answer in both -- to the character, since an answer can be a
;; refusal's message. On the JVM the host functions are java.time and
;; BigDecimal, as they always were; in ClojureScript they are written out
;; below, to the same rules. test/com/trustblocks/clause_parity_test.cljc
;; holds both to answers recorded from the JVM.

#?(:clj
   (defn- parse-dt
     "ISO-8601 string -> OffsetDateTime. Dates arrive and leave as their wire
     form; this is the only place they become platform values, and they never
     escape as such."
     ^OffsetDateTime [s]
     (when s
       (let [s (str s)]
         (try (OffsetDateTime/parse s)
              (catch Exception _
                ;; A Concerto DateTime may omit the zone; read it as UTC.
                (try (OffsetDateTime/parse (cond-> s
                                             (not (re-find #"[T ]" s)) (str "T00:00:00")
                                             true                      (str "Z")))
                     (catch Exception _
                       ;; java.time's own message quotes its internals; the
                       ;; refusal says what the clause was given.
                       (throw (ex-info (str "Not a date-time: " s) {:value s}))))))))))

#?(:cljs
   (do
     ;; A date-time here is {:secs :nanos :offset}: seconds since the epoch,
     ;; nanoseconds within the second, and the offset it was written in, in
     ;; seconds -- an OffsetDateTime's content, without a platform type.

     (defn- days-from-civil
       "Days since 1970-01-01 of a proleptic Gregorian date (H. Hinnant's
       algorithm, all integer arithmetic)."
       [y m d]
       (let [y   (if (<= m 2) (dec y) y)
             era (quot (if (neg? y) (- y 399) y) 400)
             yoe (- y (* era 400))
             doy (+ (quot (+ (* 153 (+ m (if (> m 2) -3 9))) 2) 5) (dec d))
             doe (+ (* yoe 365) (quot yoe 4) (- (quot yoe 100)) doy)]
         (+ (* era 146097) doe -719468)))

     (defn- civil-from-days [z]
       (let [z   (+ z 719468)
             era (quot (if (neg? z) (- z 146096) z) 146097)
             doe (- z (* era 146097))
             yoe (quot (- doe (quot doe 1460) (- (quot doe 36524)) (quot doe 146096)) 365)
             doy (- doe (- (+ (* 365 yoe) (quot yoe 4)) (quot yoe 100)))
             mp  (quot (+ (* 5 doy) 2) 153)
             d   (inc (- doy (quot (+ (* 153 mp) 2) 5)))
             m   (if (< mp 10) (+ mp 3) (- mp 9))]
         [(+ yoe (* era 400) (if (<= m 2) 1 0)) m d]))

     (defn- leap? [y] (and (zero? (mod y 4)) (or (pos? (mod y 100)) (zero? (mod y 400)))))

     (defn- month-days [y m]
       (case m 2 (if (leap? y) 29 28) (4 6 9 11) 30 31))

     (def ^:private dt-re
       ;; What OffsetDateTime/parse accepts, and the JVM runtime's fallback:
       ;; a date alone, or a date-time without a zone, is read as UTC.
       #"(?i)^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?(Z|[+-]\d{2}:\d{2}(?::\d{2})?)?)?$")

     (defn- invalid-dt [s]
       (throw (ex-info (str "Not a date-time: " s) {:value s})))

     (defn- parse-dt [s]
       (when s
         (let [s (str s)
               [_ y mo d h mi sec frac zone :as m] (re-find dt-re s)
               n  (fn [x] (if x (js/parseInt x 10) 0))]
           (when-not m (invalid-dt s))
           (let [[y mo d h mi sec] (map n [y mo d h mi sec])]
             (when-not (and (<= 1 mo 12) (<= 1 d (month-days y mo)) (<= h 23) (<= mi 59) (<= sec 59))
               (invalid-dt s))
             (let [offset (if (or (nil? zone) (= "Z" (.toUpperCase zone)))
                            0
                            (let [[_ sign oh om os] (re-find #"([+-])(\d{2}):(\d{2})(?::(\d{2}))?" zone)
                                  secs (+ (* 3600 (n oh)) (* 60 (n om)) (n os))]
                              (when (> secs 64800) (invalid-dt s))
                              (if (= "-" sign) (- secs) secs)))]
               {:secs   (- (+ (* 86400 (days-from-civil y mo d)) (* 3600 h) (* 60 mi) sec) offset)
                :nanos  (if frac (js/parseInt (subs (str frac "00000000") 0 9) 10) 0)
                :offset offset})))))

     (defn- pad [n width] (let [s (str n)] (str (apply str (repeat (- width (count s)) "0")) s)))

     (defn- fmt-dt
       "As DateTimeFormatter/ISO_OFFSET_DATE_TIME writes it: seconds always,
       a fraction only when there is one and no longer than it needs, and Z
       for a zero offset."
       [{:keys [secs nanos offset]}]
       (let [local   (+ secs offset)
             days    (Math/floor (/ local 86400))
             tod     (- local (* days 86400))
             [y m d] (civil-from-days days)
             frac    (when (pos? nanos) (.replace (pad nanos 9) (js/RegExp. "0+$") ""))
             abs-off (Math/abs offset)
             zone    (if (zero? offset)
                       "Z"
                       (str (if (neg? offset) "-" "+") (pad (quot abs-off 3600) 2) ":"
                            (pad (quot (mod abs-off 3600) 60) 2)
                            (when (pos? (mod abs-off 60)) (str ":" (pad (mod abs-off 60) 2)))))]
         (str (pad y 4) "-" (pad m 2) "-" (pad d 2) "T"
              (pad (quot tod 3600) 2) ":" (pad (quot (mod tod 3600) 60) 2) ":" (pad (mod tod 60) 2)
              (when frac (str "." frac))
              zone)))

     (defn- compare-dt [a b]
       (let [c (compare (:secs a) (:secs b))]
         (if (zero? c) (compare (:nanos a) (:nanos b)) c)))))

(defn- divide
  "Decimal division to an explicit number of places, half-up.

  Not `/`. The scale has to be stated because the answer depends on it, and
  stating it is what makes the result the same everywhere."
  [a b scale]
  #?(:clj
     (do
       (when (zero? (bigdec b))
         (throw (ex-info "Division by zero in clause logic" {:numerator a})))
       (double (.setScale (.divide (bigdec a) (bigdec b) ^int (int scale) RoundingMode/HALF_UP)
                          ^int (int scale) RoundingMode/HALF_UP)))
     :cljs
     ;; Exact decimal arithmetic on BigInt: each number as the decimal its
     ;; shortest form writes (as bigdec reads a double on the JVM), the
     ;; quotient rounded half away from zero, then the nearest double.
     (let [dec-of (fn [x]
                    (let [[_ sign int-part frac exp]
                          (re-find #"^(-?)(\d+)(?:\.(\d+))?(?:e([+-]?\d+))?$" (str x))]
                      [(js/BigInt (str sign int-part frac))
                       (- (count frac) (if exp (js/parseInt exp 10) 0))]))
           pow10  (fn [k] (js/BigInt (str "1" (apply str (repeat k "0")))))
           scale  (int scale)
           [ua sa] (dec-of a)
           [ub sb] (dec-of b)
           zero   (js/BigInt 0)]
       (when (= ub zero)
         (throw (ex-info "Division by zero in clause logic" {:numerator a})))
       (let [k     (+ (- sb sa) scale)
             num   (if (neg? k) ua (js* "~{} * ~{}" ua (pow10 k)))
             den   (if (neg? k) (js* "~{} * ~{}" ub (pow10 (- k))) ub)
             q     (js* "~{} / ~{}" num den)
             r     (js* "~{} % ~{}" num den)
             babs  (fn [x] (if (js* "~{} < ~{}" x zero) (js* "-~{}" x) x))
             away? (js* "~{} >= ~{}" (js* "~{} * ~{}" (js/BigInt 2) (babs r)) (babs den))
             negative (not= (js* "~{} < ~{}" num zero) (js* "~{} < ~{}" den zero))
             q     (if away? (if negative (js* "~{} - ~{}" q (js/BigInt 1)) (js* "~{} + ~{}" q (js/BigInt 1))) q)
             digits (str (babs q))
             digits (str (apply str (repeat (- (inc scale) (count digits)) "0")) digits)
             point  (- (count digits) scale)]
         (js/Number (str (when (js* "~{} < ~{}" q zero) "-")
                         (subs digits 0 point)
                         (when (pos? scale) (str "." (subs digits point)))))))))

#?(:clj
   (do
     (defn- fmt-dt ^String [^OffsetDateTime d]
       (.format d DateTimeFormatter/ISO_OFFSET_DATE_TIME))

     (defn- plus-days [dt n]
       (fmt-dt (.plusDays (parse-dt dt) (long n))))

     (defn- days-between [a b]
       (.between ChronoUnit/DAYS (parse-dt a) (parse-dt b)))

     (defn- before? [a b] (.isBefore (parse-dt a) (parse-dt b)))
     (defn- after?  [a b] (.isAfter  (parse-dt a) (parse-dt b))))

   :cljs
   (do
     (defn- plus-days [dt n]
       (fmt-dt (update (parse-dt dt) :secs + (* 86400 (long n)))))

     (defn- days-between
       "Whole days from `a` to `b`, truncated toward zero -- ChronoUnit/DAYS."
       [a b]
       (let [a  (parse-dt a)
             b  (parse-dt b)
             ds (- (:secs b) (:secs a))
             dn (- (:nanos b) (:nanos a))
             q  (quot ds 86400)]
         (cond
           (and (zero? (rem ds 86400)) (pos? ds) (neg? dn)) (dec q)
           (and (zero? (rem ds 86400)) (neg? ds) (pos? dn)) (inc q)
           :else q)))

     (defn- before? [a b] (neg? (compare-dt (parse-dt a) (parse-dt b))))
     (defn- after?  [a b] (pos? (compare-dt (parse-dt a) (parse-dt b))))))

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

;; Numbers, the same everywhere. A JSON number is a long or a double on the
;; JVM and just a number in JavaScript, so without these a clause would say
;; "$6000.0" on one and "$6000" on the other, and (= 6 6.0) would be false
;; on one and true on the other.

(defn- number-text
  "A number as plain decimal text, no exponent and no trailing \".0\":
  6000.0 -> \"6000\", 1.0E7 -> \"10000000\", 0.1 -> \"0.1\"."
  [x]
  (cond
    #?(:clj (Double/isNaN (double x)) :cljs (js/isNaN x)) "NaN"
    #?(:clj (Double/isInfinite (double x)) :cljs (not (js/isFinite x))) (if (pos? x) "Infinity" "-Infinity")

    :else
    #?(:clj (if (or (double? x) (float? x))
              (.toPlainString (.stripTrailingZeros (BigDecimal/valueOf (double x))))
              (str x))
       :cljs (let [s (str x)]
               (if-let [[_ sign int-part frac exp] (re-find #"^(-?)(\d+)(?:\.(\d+))?e([+-]\d+)$" s)]
                 (let [digits (str int-part frac)
                       point  (+ (count int-part) (js/parseInt exp 10))]
                   (str sign
                        (cond
                          (<= point 0) (str "0." (apply str (repeat (- point) "0")) digits)
                          (>= point (count digits)) (str digits (apply str (repeat (- point (count digits)) "0")))
                          :else (str (subs digits 0 point) "." (subs digits point)))))
                 s)))))

(defn- text
  "clojure.core/str, but a number prints as number-text."
  [& xs]
  (apply str (map #(if (number? %) (number-text %) %) xs)))

(defn- same?
  "clojure.core/=, but numbers compare by value: (= 6 6.0) is true, as it
  must be where the two cannot be told apart."
  [& xs]
  (if (every? number? xs) (apply == xs) (apply = xs)))

(defn- not-same? [& xs] (not (apply same? xs)))

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
  form rather than a parsed value. `str`, `=` and `not=` stand in for
  clojure.core's, so numbers read and compare the same on every platform."
  {'str           text
   '=             same?
   'not=          not-same?
   'divide        divide
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
       (catch #?(:clj Exception :cljs :default) e (ex-message e))))

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
