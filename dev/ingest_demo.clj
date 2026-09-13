(ns ingest-demo
  "End-to-end: load a real Accord template's CTO models into XTDB, then store
  and read back its sample contract. Run with:

    bin/clj -M:xtdb-jvm-opts -i dev/ingest_demo.clj"
  (:require [clojure.string]
            [cheshire.core :as json]
            [com.trustblocks.concerto.metamodel :as mm]
            [com.trustblocks.xtdb.store :as store]
            [xtdb.api :as xt]
            [xtdb.node :as xt.node]))

(def template
  (str (System/getProperty "user.home")
       "/Code/Accord/cicero-template-library/src/acceptance-of-delivery"))

(defn -main []
  (let [node      (xt.node/start-node)
        cto-files (store/model-dir-files (str template "/model"))
        sample    (json/parse-string (slurp (str template "/sample.json")))]
    (try
      (println "\n== CTO files ==")
      (doseq [f cto-files] (println " " (.getName f)))

      (println "\n== storing models as data ==")
      (doseq [f cto-files]
        (let [{:keys [namespace declarations]} (store/put-model! node f)]
          (printf "  %-45s %d declarations\n" namespace (count declarations))))

      (let [reg (store/load-registry cto-files)
            fqn (get sample "$class")]

        (println "\n== inheritance chain for the contract ==")
        (println " " (clojure.string/join "\n  -> " (mm/super-chain reg fqn)))
        (println "  identity field:" (mm/identity-field reg fqn))

        (println "\n== storing the contract ==")
        (println " " (store/put-instance! node reg sample))

        (println "\n== reading it back ==")
        (let [rt (store/get-instance node (get sample "$identifier"))]
          (println (store/instance->json rt))
          (println "\n== round-trip identical to sample.json? ==")
          (let [orig (dissoc sample "$identifier")
                back (dissoc rt "$identifier")]
            (println " " (= orig back))
            (when-not (= orig back)
              (println "  only-in-original:" (pr-str (apply dissoc orig (keys back))))
              (println "  only-in-roundtrip:" (pr-str (apply dissoc back (keys orig)))))))

        (println "\n== the guard: a bad instance must be refused ==")
        (let [bad (assoc sample "businessDays" "ten")]
          (try
            (store/put-instance! node reg bad)
            (println "  FAIL - invalid instance was accepted")
            (catch clojure.lang.ExceptionInfo e
              (println "  refused:" (:errors (ex-data e))))))

        (println "\n== schema is queryable as data ==")
        (doseq [row (sort-by first
                             (xt/q node '(-> (from :concerto-declaration
                                                   [{:xt/id fqn} concerto/kind])
                                             (return fqn concerto/kind))))]
          (printf "  %-60s %s\n"
                  (:fqn row)
                  (last (clojure.string/split (str (:concerto/kind row)) #"\.")))))
      (finally (.close node)))))

(-main)
