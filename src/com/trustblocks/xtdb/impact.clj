(ns com.trustblocks.xtdb.impact
  "What a change to a model property touches, and what a model offers that
  nothing uses.

  This joins three records: the model repository (what is declared), the
  model-use rows (what reads it), and the clause-use rows (which templates use
  the clauses that read it). It reads only."
  (:require [clojure.string :as str]
            [com.trustblocks.xtdb.model-repository :as repo]
            [com.trustblocks.xtdb.model-use :as use]
            [com.trustblocks.xtdb.template-tree :as templates]))

(defn impact
  "Who is affected if property `p` (a versioned FQN or an unversioned field)
  changes:
    {:clauses   [{:clause :version}]            clause versions that read it
     :templates [{:template :via}]              templates that read it, directly
                                                 (:via nil) or through a clause
                                                 they use ([id version])}
  A template that pins a clause version, or floats on :latest, is affected by
  the clause versions that read the property."
  [node p & {:as opts}]
  (let [rs      (use/readers node p opts)
        clauses (->> rs (filter #(= :clause (:kind %)))
                     (map (fn [{:keys [owner version]}] {:clause (first (str/split owner #"@")) :version version}))
                     distinct vec)
        direct  (->> rs (filter #(and (= :template (:kind %)) (:direct %)))
                     (map (fn [r] {:template (:owner r) :via nil})))
        through (for [{:keys [clause version]} clauses
                      u (templates/clause-users node clause opts)
                      :when (or (= :latest (:pin u)) (nil? (:pin u)) (= version (:pin u)))]
                  {:template (:template u) :via [clause version]})]
    {:clauses   clauses
     :templates (->> (concat direct through) distinct (sort-by (juxt :template #(some? (:via %)) #(str (:via %)))) vec)}))

(defn unused-properties
  "The properties the model `ns` declares that nothing reads: the unversioned
  :field of each. Properties inherited from another model count against the
  model that declares them."
  [node ns & {:as opts}]
  (let [mdl  (repo/parsed node ns)
        read (set (keys (use/fields-read node opts)))
        base (first (str/split ns #"@"))]
    (->> (:declarations mdl)
         (mapcat (fn [d] (map #(str base "." (:name d) "." (:name %)) (:properties d))))
         (remove read)
         sort vec)))
