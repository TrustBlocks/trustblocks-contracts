(ns com.trustblocks.app.landing
  (:require [com.trustblocks.lib.ui :as ui]
            [com.trustblocks.routes :as routes]))

(defn home [{:keys [session]}]
  (ui/page
   {}
   [:main {:class ["grid min-h-full flex-1 place-items-center"]}
    [:div {:class ["max-w-prose space-y-2 p-8"]}
     [:h1 {:class ["text-2xl font-semibold"]} "Trustblocks"]
     [:p "Municipal contract management, built on Concerto-validated,
          bitemporal XTDB storage."]
     (if (:uid session)
       [:p {:class ["space-x-4"]}
        [:a {:href (routes/contracts) :class ["text-blue-600 hover:underline"]}
         "View contracts →"]
        [:form {:method "post" :action (routes/signout) :class ["inline"]}
         [:button {:type "submit" :class ["text-gray-500 hover:underline"]}
          "Sign out"]]]
       [:p [:a {:href (routes/signin) :class ["text-blue-600 hover:underline"]}
            "Sign in →"]])]]))

(def module
  {:biff.ring/routes
   [[(routes/home) {:get home}]]})
