(ns com.trustblocks.lib.ui
  "Page chrome shared by every route. Biff 2's `defroute` is gone, so handlers
  are plain functions (see `com.trustblocks.routes`) and this is where the
  hiccup -> HTTP response conversion that `defroute` used to do for us lives
  instead."
  (:require [clojure.java.io :as io]
            [com.biffweb.datastar :as biff.datastar]
            [com.trustblocks.routes :as routes]
            [dev.onionpancakes.chassis.core :as chassis]
            [ring.util.response :as ring-response]))

(def default-page-opts
  {:ui/title       "Trustblocks"
   :ui/description "Municipal contract management."
   :ui/lang        "en"
   :ui/image       nil
   :ui/icon        nil})

(def ^:private datastar-script-url
  "https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.1/bundles/datastar.js")

(def ^:private workspace-nav
  [[:contracts routes/contracts "Contracts"]
   [:matters routes/matters "Matters"]
   [:templates routes/templates "Templates"]
   [:authorizations routes/authorizations "Signature Authorizations"]])

(defn workspace-header
  "The shared product header for signed-in operational pages. `active` is
  one of :contracts, :matters, :templates, :clauses, or :authorizations; keeping the navigation
  here rather than repeating it in each handler is what makes those three
  views feel like one application rather than unrelated demo pages."
  [active]
  [:header {:class ["border-b border-slate-200 bg-white"]}
   [:div {:class ["mx-auto flex max-w-6xl flex-col gap-4 px-6 py-5 sm:flex-row sm:items-end sm:justify-between"]}
    [:div
     [:a {:href (routes/contracts) :class ["text-2xl font-semibold tracking-tight text-slate-950"]}
      "Trustblocks"]
     [:p {:class ["mt-0.5 text-sm font-medium uppercase tracking-widest text-blue-700"]}
      "Municipal Contracts"]]
    [:div {:class ["flex flex-wrap items-center gap-2"]}
     [:nav {:aria-label "Primary" :class ["flex flex-wrap gap-2"]}
      (for [[key route label] workspace-nav]
        [:a {:href (route)
             :class (if (= key active)
                      ["rounded-md bg-slate-900 px-3 py-2 text-sm font-medium text-white"]
                      ["rounded-md px-3 py-2 text-sm font-medium text-slate-600 hover:bg-slate-100 hover:text-slate-950"])}
         label])]
     [:form {:method "post" :action (routes/signout)}
      [:button {:type "submit"
                :class ["rounded-md border border-slate-300 px-3 py-2 text-sm font-medium text-slate-600 hover:border-slate-400 hover:text-slate-950"]}
       "Sign out"]]]]])

(defn static-path
  "Cache-busts a /public asset by appending its last-modified time."
  [path]
  (if-some [last-modified (some-> (io/resource (str "public" path))
                                  ring-response/resource-data
                                  :last-modified
                                  (.getTime))]
    (str path "?t=" last-modified)
    path))

(defn html-response [body]
  {:status  200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body    (chassis/html body)})

(defn page
  "Renders a full HTML document around `contents`."
  [opts & contents]
  (let [{:ui/keys [title description lang image icon init-datastar]}
        (merge default-page-opts opts)]
    (html-response
     [chassis/doctype-html5
      [:html {:lang lang :class ["min-h-full h-auto"]}
       [:head
        [:meta {:charset "utf-8"}]
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
        (when title
          [[:title title]
           [:meta {:content title :property "og:title"}]])
        (when description
          [[:meta {:name "description" :content description}]
           [:meta {:property "og:description" :content description}]])
        (when image
          [[:meta {:content image :property "og:image"}]
           [:meta {:content "summary_large_image" :name "twitter:card"}]])
        (when icon
          [:link {:rel "icon" :type "image/png" :sizes "16x16" :href icon}])
        [:link {:rel "stylesheet" :href (static-path "/css/main.css")}]
        [:script {:src (static-path "/js/main.js")}]
        [:script {:type "module" :src datastar-script-url}]]
       [:body (merge {:class ["absolute min-h-full w-full flex flex-col"]}
                     (when init-datastar
                       (biff.datastar/init-opts)))
        contents]]])))

(defn app-page
  "Like `page`, but returns just the content fragment on an SSE request instead
  of a full document -- for pages that update themselves via Datastar."
  [{:keys [biff.datastar/sse-request] :as ctx} & content]
  (let [content* [:div#biff-datastar-content
                  {:class ["flex min-h-full flex-1 flex-col"]}
                  content]]
    (if sse-request
      (html-response content*)
      (page (assoc ctx :ui/init-datastar true) content*))))

(defn on-error [{:keys [status] :as ctx}]
  (-> (page
       ctx
       [:h1
        (if (= status 404)
          "Page not found."
          "Something went wrong.")])
      (assoc :status status)))
