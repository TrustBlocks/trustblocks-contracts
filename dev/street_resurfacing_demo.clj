(ns street-resurfacing-demo
  "Loads the Town of China Grove's Street Resurfacing contract, STR-27 --
  the street-resurfacing package's contract template from
  TrustBlocks/trustblocks-templates (see com.trustblocks.template.library)
  -- as the bid package exactly as issued: every blank still blank, no
  contractor, prices or streets. It has no clause yet; there is nothing to
  trigger until the pay request logic exists.

  Also ingests the package's pay application template, for its model: no
  pay request is stored until one is made under an awarded contract.

  A plain upsert of whatever the template holds, safe to call repeatedly
  (see dev/seed.clj).

    bin/clj -M:xtdb-jvm-opts -i dev/street_resurfacing_demo.clj"
  (:require [cheshire.core :as json]
            [com.trustblocks.template.archive :as archive]
            [com.trustblocks.template.library :as library]
            [com.trustblocks.xtdb.store :as store]
            [xtdb.node :as xt.node]))

(def template "street-resurfacing/contract")
(def pay-application "street-resurfacing/pay-application")
(def contract-id "china-grove-str-27")

(defn load! [node]
  (println "Ingesting the street-resurfacing package's templates ...")
  (println (archive/ingest-dir! node (library/dir template)))
  (println (archive/ingest-dir! node (library/dir pay-application)))
  (store/put-instance! node nil (json/parse-string (library/sample template))))

(defn run []
  (with-open [node (xt.node/start-node)]
    (load! node)
    (println "Stored" contract-id "--"
             (get (store/get-instance node contract-id) "contractNumber"))))

(run)
