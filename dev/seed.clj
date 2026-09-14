(ns seed
  "Starts the app for real (config -> xtdb -> ring -> datastar -> graph, same
  modules bin/clj -M:run dev boots, just not through the dev task's file
  watcher/tailwind) and loads our two real contracts -- the snow-removal
  plowing contract and the China Grove manager employment contract -- into
  its actual XTDB storage, so /contracts has real data to show instead of
  an empty list.

  late-delivery.cta is deliberately not seeded here: it's Accord's own
  reference example, kept as an engineering proof (dev/late_delivery_demo.clj
  cross-checks our Clojure clause runtime against a known-good APAP answer),
  not one of our contracts.

  Requiring the two demo namespaces below also reruns their own standalone
  verification, each against its own fresh throwaway in-memory node -- a
  free regression check, not noise -- before this seeds the real app's node
  with the same data.

    bin/clj -M:xtdb-jvm-opts -i dev/seed.clj

  Leaves the app running afterward. Visit http://localhost:8080/contracts."
  (:require [cheshire.core :as json]
            [com.trustblocks :as main]
            [com.trustblocks.template.archive :as archive]
            [com.trustblocks.xtdb.execute :as ex]
            [com.trustblocks.xtdb.store :as store]
            [manager-employment-demo :as me]
            [snow-removal-demo :as snow]))

(main/start)

(def node (:biff.xtdb/node @main/system))

(println "\nSeeding the snow-removal contract into the running app...")
(snow/populate! node)

(println "Seeding the manager-employment contract into the running app...")
(archive/ingest-dir! node "docs/examples/manager-employment-contract")
(let [sample (-> (slurp "docs/examples/manager-employment-contract/sample.json")
                 (json/parse-string)
                 (assoc "$identifier" me/contract-id))]
  (store/put-instance! node nil sample))
(ex/put-clause! node me/contract-fqn me/clojure-clause)
(ex/trigger! node me/contract-id
             {"$class"      "com.trustblocks.municipal.manageremployment@1.0.0.BoardApprovalRequest"
              "requestedAt" "2025-12-02T00:00:00.000Z"}
             "2025-12-02T00:00:00.000Z")

(println "\nSeeded. Visit http://localhost:8080/contracts")
