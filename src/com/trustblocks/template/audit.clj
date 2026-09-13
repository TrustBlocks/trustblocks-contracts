(ns com.trustblocks.template.audit
  "Audit Accord templates for ingestibility.

  A municipal contract system has to reject a bad template with a clear reason,
  not a stack trace. Every stage is therefore run inside a guard that reports
  which stage failed and why, so failures are classified rather than fatal."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [com.trustblocks.concerto.metamodel :as mm]
            [com.trustblocks.xtdb.store :as store]))

(def stages
  "In dependency order -- a template must clear each to reach the next."
  [:read-sample :parse-models :build-registry :resolve-class
   :compile-schema :validate :store :round-trip])

(defn- err [stage detail & [data]]
  {:stage stage :detail detail :data data})

(defn audit-template
  "Run one template end to end. Returns
  {:template t :ok? bool :stage <last stage reached> :detail <why it stopped>}."
  [node dir]
  (let [t (.getName (io/file dir))]
    (try
      (let [sample-file (io/file dir "sample.json")
            model-dir   (io/file dir "model")]
        (cond
          (not (.exists sample-file)) (assoc (err :read-sample "no sample.json") :template t :ok? false)
          (not (.exists model-dir))   (assoc (err :read-sample "no model/ directory") :template t :ok? false)

          :else
          (let [sample (json/parse-string (slurp sample-file))
                ctos   (store/model-dir-files model-dir)]
            (if (empty? ctos)
              (assoc (err :parse-models "model/ contains no .cto files") :template t :ok? false)
              (let [reg (store/load-registry ctos)
                    fqn (get sample "$class")]
                (cond
                  (nil? fqn)
                  (assoc (err :resolve-class "sample has no $class") :template t :ok? false)

                  (empty? (mm/super-chain reg fqn))
                  (assoc (err :resolve-class "$class not found in any loaded model"
                              {:$class fqn :namespaces (count reg)})
                         :template t :ok? false)

                  :else
                  ;; Compile the schema for its own sake first: a model the
                  ;; library refuses -- an unhandled declaration kind, an
                  ;; unresolved type, an unportable regex flag -- must be
                  ;; reported as a compile failure rather than surfacing later
                  ;; as a confusing validation error.
                  (let [_    (store/schema reg fqn)
                        errs (store/validate reg sample)]
                    (if errs
                      (assoc (err :validate "sample fails its own model" {:errors errs})
                             :template t :ok? false)
                      (let [{:keys [xt/id]} (store/put-instance! node reg sample)
                            back (store/get-instance node id :reg reg)]
                        (if (= sample back)
                          {:template t :ok? true :stage :round-trip
                           :chain (count (mm/super-chain reg fqn))
                           :declarations (count reg)}
                          (assoc (err :round-trip "round-trip differs from sample"
                                      {:only-original  (into {} (remove (fn [[k v]] (= v (get back k))) sample))
                                       :only-roundtrip (into {} (remove (fn [[k v]] (= v (get sample k))) back))})
                                 :template t :ok? false)))))))))))
      (catch Exception e
        {:template t :ok? false :stage :exception
         :detail (ex-message e)
         :data (ex-data e)}))))

(defn audit-library
  "Audit every template directory under `src-dir`."
  [node src-dir]
  (->> (.listFiles (io/file src-dir))
       (filter #(.isDirectory %))
       (sort-by #(.getName %))
       (mapv #(audit-template node %))))

(defn summarize [results]
  {:total   (count results)
   :ok      (count (filter :ok? results))
   :failed  (count (remove :ok? results))
   :by-stage (frequencies (map :stage (remove :ok? results)))})
