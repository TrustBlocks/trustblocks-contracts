;; A scratch space for inspecting things with the REPL.
(ns repl
  (:require [clojure.tools.namespace.repl :as tn-repl]
            [com.trustblocks :as main]
            [xtdb.api :as xt]))

(defn get-ctx []
  @main/system)

(defn get-node []
  (:biff.xtdb/node (get-ctx)))

(defn refresh []
  (main/stop)
  (tn-repl/refresh :after `main/start)
  :done)

(comment

  ;; Most changes will be evaluated whenever you save a file. (refresh) is
  ;; only needed if you change code that only runs at startup.
  (refresh)

  (xt/q (get-node) '(from :contract [*])))
