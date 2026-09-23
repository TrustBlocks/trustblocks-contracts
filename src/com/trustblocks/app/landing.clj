(ns com.trustblocks.app.landing
  (:require [com.trustblocks.lib.ui :as ui]
            [com.trustblocks.routes :as routes]))

(defn home [{:keys [session]}]
  (ui/page
   {:ui/title "Trustblocks — Municipal Contracts"}
   (when (:uid session) (ui/workspace-header nil))
   [:main {:class ["grid min-h-[75vh] flex-1 place-items-center bg-slate-50 px-6 py-16"]}
    [:div {:class ["max-w-3xl text-center"]}
     [:p {:class ["text-sm font-semibold uppercase tracking-[0.25em] text-blue-700"]}
      "Municipal Contracts"]
     [:h1 {:class ["mt-3 text-5xl font-semibold tracking-tight text-slate-950 sm:text-6xl"]}
      "Trustblocks"]
     [:p {:class ["mx-auto mt-5 max-w-2xl text-lg leading-8 text-slate-600"]}
      "Legally faithful municipal agreements, Concerto-validated and preserved with a bitemporal audit trail."]
     (if (:uid session)
       [:a {:href (routes/contracts)
            :class ["mt-8 inline-flex rounded-md bg-blue-700 px-5 py-3 text-sm font-semibold text-white shadow-sm hover:bg-blue-800"]}
        "Open contract warehouse →"]
       [:a {:href (routes/signin)
            :class ["mt-8 inline-flex rounded-md bg-blue-700 px-5 py-3 text-sm font-semibold text-white shadow-sm hover:bg-blue-800"]}
        "Sign in →"])]]))

(def module
  {:biff.ring/routes
   [[(routes/home) {:get home}]]})
