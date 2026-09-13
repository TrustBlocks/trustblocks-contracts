(ns com.trustblocks.app.landing
  (:require [com.trustblocks.lib.ui :as ui]
            [com.trustblocks.routes :as routes]))

(defn home [_ctx]
  (ui/page
   {}
   [:main {:class ["grid min-h-full flex-1 place-items-center"]}
    [:div {:class ["max-w-prose space-y-2 p-8"]}
     [:h1 {:class ["text-2xl font-semibold"]} "Trustblocks"]
     [:p "Municipal contract management, built on Concerto-validated,
          bitemporal XTDB storage."]]]))

(def module
  {:biff.ring/routes
   [[(routes/home) {:get home}]]})
