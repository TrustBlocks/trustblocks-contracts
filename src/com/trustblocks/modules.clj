(ns com.trustblocks.modules
  (:require [com.biffweb.config :as biff.config]
            [com.biffweb.datastar :as biff.datastar]
            [com.biffweb.graph :as biff.graph]
            [com.biffweb.ring :as biff.ring]
            [com.biffweb.xtdb :as biff.xtdb]
            [com.trustblocks.app.landing :as app.landing]
            [com.trustblocks.lib.ui :as ui]
            [com.trustblocks.model.contract :as model.contract]))

(def modules
  [{:biff.core/init {:biff.ring/on-error #'ui/on-error}}
   (biff.config/module)
   (biff.xtdb/module)
   (biff.ring/module)
   (biff.datastar/module)
   (biff.graph/module)
   model.contract/module
   app.landing/module])

;; Only modules with a :biff.core/start function need an entry here, in the
;; order they should start. biff.ring/module goes last so the HTTP listener
;; doesn't open until everything it depends on is up.
(def start-order
  [:biff.config/module
   :biff.xtdb/module
   :biff.ring/module])
