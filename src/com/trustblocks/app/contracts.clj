(ns com.trustblocks.app.contracts
  "Read-only pages over what's actually stored: a list, a search over it,
  one contract's data/state/history, and its $class's own declaration.
  Nothing here writes -- ingestion and execution happen through the
  REPL/dev scripts today (dev/late_delivery_demo.clj,
  dev/manager_employment_demo.clj, dev/seed.clj); this is the warehouse's
  front window, not its loading dock."
  (:require [clojure.string :as str]
            [com.trustblocks.concerto.metamodel :as mm]
            [com.trustblocks.lib.ui :as ui]
            [com.trustblocks.model.graph :as model.graph]
            [com.trustblocks.routes :as routes]
            [com.trustblocks.xtdb.execute :as execute]
            [com.trustblocks.xtdb.store :as store]))

;; ---------------------------------------------------------------- rendering

(declare render-instance)

(defn- render-value
  "An instance value as hiccup. Nested concepts (a map carrying its own
  $class) and arrays of them recurse; everything else -- strings, numbers,
  booleans, enum tags, DateTime strings, relationship ids -- is text. Dates
  are shown in their wire ISO-8601 form rather than reformatted, same
  principle as everywhere else in this app: a DateTime is a string until
  something has a specific reason to parse it, and a read-only viewer
  doesn't."
  [reg v]
  (cond
    (map? v)
    (render-instance reg v)

    (and (vector? v) (seq v) (every? map? v))
    [:div {:class ["space-y-3 border-l-2 border-gray-200 pl-4"]}
     (for [item v] (render-instance reg item))]

    (vector? v)
    (str/join ", " v)

    :else (str v)))

(defn render-instance
  "A Concerto instance as a read-only field list, in the $class's own
  declared property order (com.trustblocks.model.graph/own-properties) --
  the order a human reading the source contract would expect, not JSON key
  order (unspecified) or alphabetical (meaningless). $class/$identifier
  aren't shown; they're the type and the id, not a term of the contract."
  [reg instance]
  (let [fqn (get instance "$class")]
    [:dl {:class ["divide-y divide-gray-100"]}
     (for [prop (model.graph/own-properties reg fqn)
           :let [pname (:name prop)
                 v     (get instance pname)]
           :when (some? v)]
       [:div {:class ["py-2 grid grid-cols-3 gap-4"]}
        [:dt {:class ["text-sm text-gray-500"]} pname]
        [:dd {:class ["col-span-2 text-sm"]} (render-value reg v)]])]))

;; ---------------------------------------------------------------- list

(defn- matches? [q {:keys [id class]}]
  (or (str/blank? q)
      (str/includes? (str/lower-case (str id)) (str/lower-case q))
      (str/includes? (str/lower-case (str class)) (str/lower-case q))))

(defn- query-param
  "biff.ring's default middleware may or may not keywordize :params
  depending on content type, so check both spellings rather than guess --
  the same defensive pattern com.biffweb.ring.impl.middleware/wrap-nippy-params
  itself uses for the same reason."
  [ctx k]
  (or (get-in ctx [:query-params k])
      (get-in ctx [:params k])
      (get-in ctx [:params (keyword k)])))

(defn- contract-row [{:keys [id class]}]
  [:tr {:class ["border-t"]}
   [:td {:class ["py-2"]}
    [:a {:href (routes/contract id) :class ["text-blue-600 hover:underline"]} id]]
   [:td {:class ["py-2 text-gray-600"]} class]])

(defn- contracts-table [contracts]
  [:table {:class ["w-full text-sm"]}
   [:thead
    [:tr {:class ["text-left text-gray-500"]}
     [:th {:class ["pb-2"]} "Id"]
     [:th {:class ["pb-2"]} "Type"]]]
   [:tbody (map contract-row contracts)]])

(defn list-page [{:keys [biff.xtdb/node] :as ctx}]
  (let [q         (or (query-param ctx "q") "")
        contracts (->> (store/list-instances node)
                      (filter #(matches? q %))
                      (sort-by :id))]
    (ui/page
     {:ui/title "Contracts"}
     [:main {:class ["mx-auto max-w-3xl p-8 space-y-6"]}
      [:h1 {:class ["text-2xl font-semibold"]} "Contracts"]
      [:form {:method "get" :action (routes/contracts)}
       [:input {:type "search" :name "q" :value q
                :placeholder "Search by id or type"
                :class ["border rounded px-3 py-1.5 w-full max-w-sm"]}]]
      (if (empty? contracts)
        [:p {:class ["text-gray-500"]}
         (if (str/blank? q) "No contracts stored yet." "No contracts match that search.")]
        (contracts-table contracts))])))

;; ---------------------------------------------------------------- viewer

(defn- id-from-uri
  "The :id path segment, read from the raw request URI instead of
  :path-params.

  biff.ring's default middleware stack includes wrap-path-param-uuids,
  which runs every :path-params value through a UUID-decode attempt:
  strings of exactly 22 characters get base64-url-decoded, and if that
  happens to produce exactly 16 bytes, the decoded bytes are reinterpreted
  as a UUID and silently replace the original string. 'china-grove-gover-2025'
  is exactly 22 characters of URL-safe-base64 alphabet and happens to
  decode to 16 bytes, so it gets corrupted into an unrelated UUID before a
  handler ever sees it -- while 'snow-2026-014' (13 chars) does not. This
  isn't specific to these two ids; any future contract id landing on that
  22-char/16-byte coincidence would misfire the same way. Reading the id
  from :uri sidesteps that middleware entirely, since it only touches
  :path-params."
  [ctx]
  (-> (:uri ctx)
      (str/replace-first #"^/contracts/" "")
      (str/split #"/")
      first))

(defn- history-entry [{:keys [at request response]}]
  [:div {:class ["border rounded p-3"]}
   [:p {:class ["text-xs text-gray-500 mb-2"]} (str at)]
   [:div {:class ["grid grid-cols-2 gap-4 text-sm"]}
    [:div
     [:p {:class ["text-gray-500"]} "request"]
     [:pre {:class ["whitespace-pre-wrap"]} (pr-str request)]]
    [:div
     [:p {:class ["text-gray-500"]} "response"]
     [:pre {:class ["whitespace-pre-wrap"]} (pr-str response)]]]])

(defn show-page [{:keys [biff.xtdb/node] :as ctx}]
  (let [id       (id-from-uri ctx)
        reg      (store/registry-from-xtdb node)
        instance (store/get-instance node id :reg reg)]
    (if-not instance
      (ui/on-error {:status 404})
      (let [fqn     (get instance "$class")
            state   (execute/current-state node id :reg reg)
            history (execute/history node id)]
        (ui/page
         {:ui/title (str id " - Trustblocks")}
         [:main {:class ["mx-auto max-w-3xl p-8 space-y-8"]}
          [:div
           [:a {:href (routes/contracts) :class ["text-sm text-blue-600 hover:underline"]}
            "<- All contracts"]]
          [:div
           [:h1 {:class ["text-2xl font-semibold"]} id]
           [:p {:class ["text-gray-500"]}
            fqn " - "
            [:a {:href (routes/contract-model id) :class ["text-blue-600 hover:underline"]}
             "view model"]]]

          [:section
           [:h2 {:class ["text-lg font-medium mb-2"]} "Data"]
           (render-instance reg instance)]

          [:section
           [:h2 {:class ["text-lg font-medium mb-2"]} "Current state"]
           (if state
             (render-instance reg state)
             [:p {:class ["text-gray-500"]} "Not yet executed."])]

          [:section
           [:h2 {:class ["text-lg font-medium mb-2"]} (str "History (" (count history) ")")]
           (if (empty? history)
             [:p {:class ["text-gray-500"]} "No executions recorded."]
             [:div {:class ["space-y-4"]} (map history-entry history)])]])))))

;; ---------------------------------------------------------------- model

(defn- render-property [prop]
  (let [kind   (mm/metamodel-type (:$class prop))
        target (when (= kind "ObjectProperty") (mm/type-fqn (:type prop)))]
    [:tr {:class ["border-t"]}
     [:td {:class ["py-1.5 pr-4 font-mono text-sm"]} (:name prop)]
     [:td {:class ["py-1.5 pr-4 text-sm text-gray-600"]}
      (cond-> (or target (str/replace kind #"Property$" ""))
        (:isArray prop) (str "[]"))]
     [:td {:class ["py-1.5 text-sm text-gray-500"]} (when (:isOptional prop) "optional")]]))

(defn model-page [{:keys [biff.xtdb/node] :as ctx}]
  (let [id       (id-from-uri ctx)
        reg      (store/registry-from-xtdb node)
        instance (store/get-instance node id :reg reg)]
    (if-not instance
      (ui/on-error {:status 404})
      (let [fqn   (get instance "$class")
            chain (mm/super-chain reg fqn)]
        (ui/page
         {:ui/title (str fqn " model")}
         [:main {:class ["mx-auto max-w-3xl p-8 space-y-6"]}
          [:div
           [:a {:href (routes/contract id) :class ["text-sm text-blue-600 hover:underline"]}
            (str "<- " id)]]
          [:div
           [:h1 {:class ["text-2xl font-semibold font-mono"]} fqn]
           [:p {:class ["text-gray-500"]}
            (str "Supertype chain: " (str/join " -> " (reverse chain)))]]
          [:table {:class ["w-full"]}
           [:thead
            [:tr {:class ["text-left text-gray-500"]}
             [:th {:class ["pb-2"]} "Property"] [:th {:class ["pb-2"]} "Type"] [:th {:class ["pb-2"]} ""]]]
           [:tbody (map render-property (model.graph/own-properties reg fqn))]]])))))

(def module
  {:biff.ring/routes
   [[(routes/contracts) {:get list-page}]
    [(routes/contract) {:get show-page}]
    [(routes/contract-model) {:get model-page}]]})
