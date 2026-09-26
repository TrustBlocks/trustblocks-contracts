(ns com.trustblocks.xtdb.parser-test
  "The app parses CTO with concerto-clj's instaparse port of Accord's grammar
  (see store/default-parser). This holds it to Accord's own CLI, the
  reference implementation, on every model the app actually loads -- both
  templates, the app's own identity model, the walkthrough's snow-removal
  model, and Accord's late-delivery archive -- as concerto-clj's
  script/differential.clj does across Accord's template library.

  Needs the CLI, which is the point: when it cannot be found this says so
  rather than passing vacuously."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [com.trustblocks.concerto.cto :as cto]
            [com.trustblocks.template.archive :as archive]
            [com.trustblocks.template.library :as library]
            [com.trustblocks.xtdb.store :as store]))

(defn- models-the-app-loads []
  (concat (store/model-dir-files (io/file (library/dir "manager-employment-contract") "model"))
          (store/model-dir-files (io/file (library/dir "street-resurfacing/contract") "model"))
          (store/model-dir-files (io/file (archive/unzip! "docs/examples/late-delivery.cta") "model"))
          [(io/file "model/identity.cto")
           (io/file "docs/examples/snow-removal.cto")]))

(deftest the-clojure-parser-agrees-with-accords-cli
  (let [cli (cto/find-cli)]
    (is (some? cli) "Accord's concerto CLI is needed to check the Clojure parser against it -- run npm install")
    (when cli
      (let [files (models-the-app-loads)]
        (is (<= 15 (count files)))
        (doseq [f files]
          (testing (str f)
            (is (= (cto/parse f :parser :cli :cli cli)
                   (cto/parse f :parser :clojure)))))))))

(deftest the-app-parses-with-the-clojure-parser-by-default
  (is (= :clojure (store/default-parser))))
