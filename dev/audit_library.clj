(ns audit-library
  "Sweep the whole Accord template library through the ingestion pipeline.

    bin/clj -M:xtdb-jvm-opts -i dev/audit_library.clj"
  (:require [clojure.string :as str]
            [com.trustblocks.template.audit :as audit]
            [xtdb.node :as xt.node]))

(def src-dir (str (System/getProperty "user.home")
                  "/Code/Accord/cicero-template-library/src"))

(defn- truncate [s n]
  (let [s (str s)]
    (if (< n (count s)) (str (subs s 0 (- n 1)) "…") s)))

(let [node    (xt.node/start-node)
      results (audit/audit-library node src-dir)
      summary (audit/summarize results)]
  (try
    (println "\n=================== SUMMARY ===================")
    (printf "%d templates: %d ingested, %d failed\n"
            (:total summary) (:ok summary) (:failed summary))
    (println "\nfailures by stage:")
    (doseq [[stage n] (sort-by (comp - val) (:by-stage summary))]
      (printf "  %-16s %d\n" stage n))

    (println "\n=================== FAILURES ===================")
    (doseq [{:keys [template stage detail]} (remove :ok? results)]
      (printf "  %-44s %-14s %s\n" template stage (truncate detail 60)))

    (println "\n============ FAILURE DETAIL (grouped) ============")
    (doseq [[stage group] (group-by :stage (remove :ok? results))]
      (println "\n---" stage "---")
      (doseq [{:keys [template data]} (take 4 group)]
        (println " " template)
        (when data
          (println "   " (truncate (pr-str data) 400)))))

    (println "\n=================== PASSED ===================")
    (println " " (str/join ", " (map :template (filter :ok? results))))
    (finally (.close node))))
