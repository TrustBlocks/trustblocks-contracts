(ns com.trustblocks.app.matters
  "Matters, read-only: each matter held up against its package's manifest
  -- what the package calls for, phase by phase, and what is on record for
  each; the matter's deadlines and timeline, as of any day; the law it
  rests on and the guides to it; and the manifest itself, as EDN or JSON.
  Then its documents and where each stands in its lifecycle, and the signed
  record of how it got there -- every designation, receipt and attestation
  naming the matter, with who signed it, under what authority, and the
  stored file it names.

  The timeline and deadlines are the matter's terms' (the contract
  dashboard's, com.trustblocks.app.contracts), which already take in every
  document of the matter.

  Also serves those stored files (/documents/:id), re-hashed on every read
  (com.trustblocks.xtdb.document/content): a file that no longer matches
  the hash a credential names is refused, never served.

  Like the contract pages, everything here is behind signin. Nothing here
  writes; a matter moves only through com.trustblocks.xtdb.advance."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [com.trustblocks.app.contracts :as contracts]
            [com.trustblocks.lib.code :as code]
            [com.trustblocks.lib.middleware :as mid]
            [com.trustblocks.lib.ui :as ui]
            [com.trustblocks.routes :as routes]
            [com.trustblocks.xtdb.certification :as cert]
            [com.trustblocks.xtdb.document :as document]
            [com.trustblocks.xtdb.execute :as execute]
            [com.trustblocks.xtdb.identity :as identity]
            [com.trustblocks.model.manifest :as manifest]
            [com.trustblocks.model.timeline :as timeline]
            [com.trustblocks.xtdb.matter :as matter]
            [com.trustblocks.xtdb.receipt :as receipt]
            [com.trustblocks.xtdb.store :as store]))

(def ^:private section
  ["rounded-xl border border-slate-200 bg-white p-6 shadow-sm"])

(defn- date [instant]
  (when instant (subs (str instant) 0 10)))

(defn- who
  "'Teresa Barringer, Zoning Administrator', or the bare did."
  [node did]
  (if-let [o (identity/by-did node did)]
    (str (:identity/name o) ", " (:identity/office o))
    did))

(defn- label [s]
  (-> s str/lower-case (str/replace "_" " ") str/capitalize))

(defn- file-link [node id]
  (when id
    (let [{:document/keys [filename]} (document/get-document node id)]
      [:a {:href (routes/stored-document id)
           :class ["font-mono text-xs text-blue-700 hover:underline"]
           :title id}
       (or filename (str (subs id 0 (min 19 (count id))) "…"))])))

(def ^:private status-classes
  {"PAID"      ["bg-emerald-50 text-emerald-700 ring-emerald-200"]
   "RETURNED"  ["bg-slate-100 text-slate-600 ring-slate-200"]})

(defn- status-badge [status]
  [:span {:class (into ["inline-flex rounded-full px-2.5 py-0.5 text-xs font-semibold ring-1 ring-inset"]
                       (get status-classes status ["bg-blue-50 text-blue-700 ring-blue-200"]))}
   (or status "—")])

;; ------------------------------------------------------------------ list

(defn list-page [{:keys [biff.xtdb/node]}]
  (let [matters (matter/all node)]
    (ui/page
     {:ui/title "Matters — Trustblocks"}
     (ui/workspace-header :matters)
     [:main {:class ["mx-auto w-full max-w-6xl px-6 py-8"]}
      [:p {:class ["text-sm font-semibold uppercase tracking-widest text-blue-700"]} "Matters"]
      [:h1 {:class ["mt-1 text-3xl font-semibold tracking-tight text-slate-950"]} "Open Matters"]
      [:p {:class ["mt-2 max-w-2xl text-sm leading-6 text-slate-600"]}
       "A matter is the live set of documents under one set of terms -- a contract and every pay request made under it -- and the signed record of each step."]
      (if (empty? matters)
        [:div {:class ["mt-8 rounded-xl border border-dashed border-slate-300 bg-white p-10 text-center text-slate-500"]}
         "No matters are open yet."]
        [:div {:class ["mt-8 space-y-4"]}
         (for [{:keys [xt/id] :matter/keys [name package terms]} matters]
           [:a {:href (routes/matter id)
                :class (into ["block hover:border-blue-300"] section)}
            [:p {:class ["text-lg font-semibold text-slate-950"]} (or name id)]
            [:p {:class ["mt-1 text-sm text-slate-500"]}
             (str/join " · " (remove nil? [id package (str "terms: " terms)
                                           (str (count (matter/documents node id)) " documents")]))]])])])))

;; ------------------------------------------------------------------ manifest

(def ^:private standing-styles
  {:on-record     ["bg-emerald-50 text-emerald-700 ring-emerald-200" "On record"]
   :not-on-record ["bg-amber-50 text-amber-800 ring-amber-200" "Not on record"]
   :not-yet       ["bg-slate-50 text-slate-500 ring-slate-200" "Not yet"]
   :optional      ["bg-slate-50 text-slate-500 ring-slate-200" "Optional"]})

(def ^:private treatment-text
  {"TERMS"    "The terms"
   "TEMPLATE" "Modelled"
   "STORED"   "Received"})

(def ^:private count-text
  {"ONE" "One" "ONE_PER_MONTH" "One a month" "ONE_PER_PAYEE" "One per payee" "ANY" "Any"})

(defn- pill [[classes text]]
  [:span {:class ["inline-flex shrink-0 justify-center whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-semibold ring-1 ring-inset" classes]}
   text])

(defn- entry-line [node {:keys [kind id at file status terms from document]}]
  [:li {:class ["flex flex-wrap items-baseline gap-x-2 text-xs text-slate-600"]}
   (when at [:span {:class ["font-mono text-slate-500"]} (date at)])
   (case kind
     :instance
     (list [:a {:href (routes/contract id) :class ["font-mono text-blue-700 hover:underline"]} id]
           (when terms [:span "(the matter's terms)"])
           (when status (status-badge status))
           (file-link node file))
     :receipt
     (list [:span (str "received from " from)]
           (file-link node file)
           (when document
             (list [:span "with"]
                   [:a {:href (routes/contract document) :class ["font-mono text-blue-700 hover:underline"]} document]))))])

(defn- checklist-row [node refs {:keys [title exhibit treatment supplied-by received-under note
                                        references on-record standing fields] :as d}]
  (let [n (get d :count)]
    [:tr {:class ["align-top"]}
     [:td {:class ["py-3 pr-4"]}
      [:p {:class ["font-medium text-slate-950"]} title
       (when exhibit [:span {:class ["ml-2 text-xs font-normal text-slate-500"]} (str "Exhibit " exhibit)])]
      (when note [:p {:class ["mt-1 max-w-xl text-xs leading-5 text-slate-500"]} note])
      (when (seq fields)
        [:p {:class ["mt-1 text-xs text-slate-500"]}
         "Kept as data: " (str/join ", " (map :name fields))])
      (when (seq references)
        [:p {:class ["mt-1 text-xs text-slate-500"]}
         (interpose ", " (for [r references]
                           [:a {:href (str "#ref-" r) :class ["hover:underline"]} (get-in refs [r :citation] r)]))])]
     [:td {:class ["py-3 pr-4 text-xs text-slate-600"]}
      (treatment-text treatment treatment)
      [:br] (label (or supplied-by "")) " · " (count-text n n)
      (when received-under
        [:span {:class ["block text-slate-500"]} (str/lower-case (label received-under))])]
     [:td {:class ["py-3 pr-4"]}
      (if (seq on-record)
        [:ul {:class ["space-y-1"]} (map #(entry-line node %) on-record)]
        [:span {:class ["text-xs text-slate-400"]} "—"])]
     [:td {:class ["py-3 text-right"]} (pill (standing-styles standing))]]))

(defn- checklist-section [node m matter-record as-of today]
  (let [{:keys [documents unlisted]} (manifest/checklist node m matter-record :at as-of)
        refs    (into {} (map (juxt :id identity) (:references m)))
        missing (count (filter #(= :not-on-record (:standing %)) documents))]
    [:section {:id "checklist" :class section}
     [:div {:class ["mb-1 flex flex-wrap items-baseline justify-between gap-2"]}
      [:h2 {:class ["text-lg font-semibold text-slate-950"]}
       (if (= (date as-of) (date today)) "What the package calls for" (str "What was on record on " (date as-of)))]
      [:p {:class ["text-sm text-slate-500"]}
       (str (count (filter #(= :on-record (:standing %)) documents)) " of " (count documents) " on record"
            (when (pos? missing) (str " · " missing " required, not on record")))]]
     [:p {:class ["mb-4 text-sm text-slate-500"]}
      "Each document the manifest lists, and what is on record for it. Shown, not enforced: a required document not on record is something to see, and what follows is the Town's decision."]
     (for [{phase :name :keys [description]} (:phases m)
           :let [rows (filter #(= phase (:phase %)) documents)]
           :when (seq rows)]
       [:div {:class ["mt-6 first:mt-0"]}
        [:h3 {:class ["text-sm font-semibold uppercase tracking-widest text-blue-700"]} (label phase)]
        [:p {:class ["mt-1 text-sm text-slate-500"]} description]
        ;; Fixed columns, so each phase's table lines up with the others.
        [:table {:class ["mt-2 w-full table-fixed text-left text-sm"]}
         [:thead {:class ["text-xs uppercase tracking-wide text-slate-500"]}
          [:tr [:th {:class ["w-[44%] py-2"]} "Document"] [:th {:class ["w-[17%]"]} "Treatment"]
           [:th {:class ["w-[27%]"]} "On record"] [:th {:class ["w-[12%]"]} ""]]]
         [:tbody {:class ["divide-y divide-slate-100"]} (map #(checklist-row node refs %) rows)]]])
     (when (seq unlisted)
       [:div {:class ["mt-6"]}
        [:h3 {:class ["text-sm font-semibold uppercase tracking-widest text-slate-500"]} "On record, not in the manifest"]
        [:ul {:class ["mt-2 space-y-1"]}
         (for [[role entries] unlisted, e entries]
           [:li {:class ["flex gap-2 text-sm"]} [:span {:class ["text-slate-500"]} (or role "no role")]
            [:ul (entry-line node e)]])]])]))

(defn- milestones-section [m]
  [:section {:class section}
   [:h2 {:class ["mb-1 text-lg font-semibold text-slate-950"]} "Milestones"]
   [:p {:class ["mb-3 text-sm text-slate-500"]}
    "Points in the matter's life, and what dates each. Where a document sets the date -- the Completion Date, counted from the Notice to Proceed -- it is a deadline above."]
   [:ol {:class ["divide-y divide-slate-100"]}
    (for [{:keys [name phase when]} (:milestones m)]
      [:li {:class ["flex gap-4 py-2 text-sm"]}
       [:span {:class ["w-28 shrink-0 text-xs font-semibold uppercase tracking-wide text-slate-500"]} (label phase)]
       [:span {:class ["w-48 shrink-0 font-medium text-slate-950"]} name]
       [:span {:class ["text-slate-600"]} when]])]])

(defn- references-section [m]
  [:section {:class section}
   [:h2 {:class ["mb-3 text-lg font-semibold text-slate-950"]} "The law and standards it rests on"]
   [:ul {:class ["divide-y divide-slate-100"]}
    (for [{:keys [id citation kind subject]} (:references m)]
      [:li {:id (str "ref-" id) :class ["flex gap-4 py-2 text-sm target:bg-yellow-50"]}
       [:span {:class ["w-20 shrink-0 text-xs font-semibold uppercase tracking-wide text-slate-500"]} (label kind)]
       [:div
        [:p {:class ["font-medium text-slate-950"]} citation]
        [:p {:class ["text-slate-600"]} subject]]])]])

(defn- guides-section [m]
  (let [refs (into {} (map (juxt :id identity) (:references m)))]
    [:section {:class section}
     [:h2 {:class ["mb-1 text-lg font-semibold text-slate-950"]} "Guides"]
     [:p {:class ["mb-3 text-sm text-slate-500"]}
      "Where to look, and what to ask -- for a person, or an agent reading this matter. Guidance, not rules: nothing checks or acts on it."]
     [:div {:class ["space-y-2"]}
      (for [{:keys [topic summary references where prompts]} (:guides m)]
        [:details {:class ["rounded-lg border border-slate-200 p-4"]}
         [:summary {:class ["cursor-pointer font-medium text-slate-950"]} topic]
         [:p {:class ["mt-2 text-sm text-slate-700"]} summary]
         (when where [:p {:class ["mt-2 text-sm text-slate-600"]} [:span {:class ["font-medium"]} "Where: "] where])
         (when (seq references)
           [:p {:class ["mt-1 text-sm text-slate-600"]} [:span {:class ["font-medium"]} "Law: "]
            (interpose ", " (for [r references]
                              [:a {:href (str "#ref-" r) :class ["text-blue-700 hover:underline"]}
                               (get-in refs [r :citation] r)]))])
         [:ul {:class ["mt-2 list-disc space-y-1 pl-5 text-sm text-slate-700"]}
          (for [p prompts] [:li p])]])]]))

(defn- manifest-source-section [id package fmt]
  (let [edn-src (manifest/source package)
        json?   (= "json" fmt)
        tab     (fn [f text]
                  [:a {:href (str (routes/matter id) (when (= f "json") "?manifest=json") "#manifest")
                       :class ["rounded-md px-3 py-1 text-sm font-medium"
                               (if (= json? (= f "json")) "bg-slate-900 text-white" "text-slate-600 hover:bg-slate-100")]}
                   text])]
    [:section {:id "manifest" :class section}
     [:div {:class ["mb-3 flex flex-wrap items-center justify-between gap-3"]}
      [:div
       [:h2 {:class ["text-lg font-semibold text-slate-950"]} "The manifest"]
       [:p {:class ["text-sm text-slate-500"]}
        (if json?
          "As Concerto JSON: camelCase keys and a $class on every object, typed by com.trustblocks.manifest@1.0.0.Manifest -- for Accord's tools and anything outside Clojure."
          (str "As written, in EDN: packages/" package "/manifest.edn in trustblocks-templates, at the commit this app pins."))]]
      [:div {:class ["flex gap-1 rounded-lg border border-slate-200 p-1"]}
       (tab "edn" "EDN") (tab "json" "JSON")]]
     (code/data-listing
      (if json?
        (json/generate-string (manifest/->concerto (manifest/for-package package)) {:pretty true})
        edn-src))]))

;; ------------------------------------------------------------------ one matter

(defn- designations [node record]
  (let [ds (filter #(= cert/designation-class (get % "$class")) record)]
    [:section {:class section}
     [:h2 {:class ["mb-3 text-lg font-semibold text-slate-950"]} "Designations"]
     (if (empty? ds)
       [:p {:class ["text-slate-500"]} "No one acts on this matter by designation."]
       (for [d ds]
         [:div {:class ["rounded-lg border border-slate-200 p-4"]}
          [:p {:class ["font-medium text-slate-950"]} (who node (get d "subject"))]
          [:p {:class ["text-sm text-slate-600"]} (get d "capacity")]
          [:p {:class ["mt-1 text-sm text-slate-600"]}
           (str "Designated by " (who node (get d "issuer")) ", "
                (date (get d "valid-from")) " to " (date (get d "valid-until")) ", to "
                (str/join ", " (map (comp str/lower-case label) (get d "authority"))) ".")]
          [:p {:class ["mt-1 text-xs text-slate-400"]}
           "Counts only while the designator holds the authority themselves; cannot be passed on."]]))]))

(defn- state-details
  "Whatever else the document's state holds beyond its status, as its
  template defines it -- 'approvedPayItems: 3.1, 5.1' for a pay request."
  [state]
  (->> (dissoc state "$class" "$identifier" "status")
       (remove (fn [[k v]] (or (nil? v) (= v (get state "$identifier")) (str/ends-with? k "Id"))))
       (map (fn [[k v]] (str k ": " (if (sequential? v) (str/join ", " v) v))))
       (str/join "; ")))

(defn- documents [node matter-id]
  (let [docs (matter/documents node matter-id)]
    [:section {:class section}
     [:h2 {:class ["mb-3 text-lg font-semibold text-slate-950"]} (str "Documents (" (count docs) ")")]
     [:table {:class ["w-full text-left text-sm"]}
      [:thead {:class ["text-xs uppercase tracking-wide text-slate-500"]}
       [:tr [:th {:class ["py-2"]} "Document"] [:th "Role"] [:th "Joined"] [:th "Status"]
        [:th "State"] [:th "File"]]]
      [:tbody {:class ["divide-y divide-slate-100"]}
       (for [{:keys [xt/id role joined stored-document]} docs
             :let [state (execute/current-state node id)]]
         [:tr
          [:td {:class ["py-2"]}
           [:a {:href (routes/contract id) :class ["font-mono text-blue-700 hover:underline"]} id]]
          [:td role]
          [:td (date joined)]
          [:td (status-badge (get state "status"))]
          [:td {:class ["text-xs text-slate-600"]} (state-details state)]
          [:td (file-link node stored-document)]])]]]))

(defn- evidence-line [node {:strs [type issuer credentialSubject]}]
  (when type
    (str "Under " (if (= "Designation" type)
                    (str "designation by " (who node issuer))
                    (str "office grant from " (if (str/starts-with? issuer "did:web") "the Town" (who node issuer))))
         (when-let [a (seq (get credentialSubject "authority"))]
           (str " (" (str/join ", " (map (comp str/lower-case label) a)) ")")))))

(defn- record-entry [node entry]
  (let [cls (get entry "$class")

        [kind text]
        (condp = cls
          receipt/receipt-class
          ["Receipt" (str "Received " (or (get entry "description") "a document")
                          " from " (get entry "receivedFrom") ".")]
          cert/attestation-class
          ["Attestation" (str (get entry "statement") " — " (get entry "contractId"))]
          cert/designation-class
          ["Designation" (str "Designated " (who node (get entry "subject")) " as "
                              (get entry "capacity") ".")]
          [cls ""])]
    [:li {:class ["py-3"]}
     [:div {:class ["flex flex-wrap items-baseline gap-x-3"]}
      [:span {:class ["w-24 font-mono text-xs text-slate-500"]} (date (get entry "valid-from"))]
      [:span {:class ["text-xs font-semibold uppercase tracking-wide text-blue-700"]} kind]
      [:span {:class ["text-sm text-slate-900"]} (who node (get entry "issuer"))]]
     [:p {:class ["ml-[6.75rem] text-sm text-slate-700"]} text]
     [:p {:class ["ml-[6.75rem] flex flex-wrap gap-x-3 text-xs text-slate-500"]}
      (when-let [e (evidence-line node (get entry "evidence"))] [:span e])
      (file-link node (get entry "documentHash"))
      [:span {:class ["text-emerald-700"]} "✓ signature verified"]]]))

(defn show-page
  "The matter: held up against its package's manifest, as of a day (the
  slider, ?as-of=), with its deadlines, timeline, law and guides; then its
  documents and signed record. ?manifest=json shows the manifest as JSON."
  [{:keys [biff.xtdb/node] :as ctx}]
  (let [id (routes/matter-id ctx)]
    (if-let [{:matter/keys [name package terms] :as matter-record} (matter/get-matter node id)]
      (let [record (sort-by #(get % "valid-from") (cert/matter-record node id))
            reg    (store/registry-from-xtdb node)
            m      (manifest/for-package package)
            today  (java.time.Instant/now)
            as-of  (or (some-> (contracts/query-param ctx "as-of") not-empty
                               (#(try (contracts/end-of-day %) (catch Exception _ nil))))
                       today)
            as-of  (if (.isAfter ^java.time.Instant as-of today) today as-of)]
        (ui/page
         {:ui/title (str (or name id) " — Trustblocks")}
         (ui/workspace-header :matters)
         [:main {:class ["mx-auto w-full max-w-6xl space-y-8 px-6 py-8"]}
          [:div
           [:a {:href (routes/matters) :class ["text-sm font-medium text-blue-700 hover:underline"]}
            "← All matters"]]
          [:div {:class ["border-b border-slate-200 pb-7"]}
           [:p {:class ["text-sm font-semibold uppercase tracking-widest text-blue-700"]} "Matter"]
           [:h1 {:class ["mt-1 text-3xl font-semibold tracking-tight text-slate-950"]} (or name id)]
           [:p {:class ["mt-2 text-sm text-slate-500"]}
            [:span {:class ["font-mono"]} id] (when package (str " · package " package)) " · terms "
            [:a {:href (routes/contract terms) :class ["font-mono text-blue-700 hover:underline"]} terms]
            (when-let [c (get (store/get-instance node terms) "contractorName")]
              (str " · " c))]
           (when m
             [:div {:class ["mt-3 max-w-3xl"]}
              [:p {:class ["text-sm font-medium text-slate-800"]} (:title m)]
              [:p {:class ["mt-1 text-sm leading-6 text-slate-600"]} (:summary m)]])]
          (contracts/as-of-slider (routes/matter id) (timeline/items node reg terms today) as-of today)
          (if m
            (checklist-section node m matter-record as-of today)
            [:section {:class section}
             [:p {:class ["text-sm text-slate-500"]}
              (if package
                (str "The package " package " has no manifest, so there is nothing to hold this matter up against.")
                "This matter names no package, so it has no manifest.")]])
          (contracts/deadlines-section node reg terms today)
          (contracts/timeline-section node reg terms as-of today)
          [:p {:class ["-mt-5 text-sm text-slate-500"]}
           "Notes on the matter are made on its terms' "
           [:a {:href (str (routes/contract terms) "#timeline") :class ["text-blue-700 hover:underline"]} "dashboard"] "."]
          (documents node id)
          (designations node record)
          [:section {:class section}
           [:h2 {:class ["mb-1 text-lg font-semibold text-slate-950"]} (str "Signed Record (" (count record) ")")]
           [:p {:class ["mb-3 text-sm text-slate-500"]}
            "Every credential naming this matter, by the date of the act, each verified against its signer's key."]
           [:ol {:class ["divide-y divide-slate-100"]} (map #(record-entry node %) record)]]
          (when m
            (list (milestones-section m)
                  (references-section m)
                  (guides-section m)
                  (manifest-source-section id package (contracts/query-param ctx "manifest"))))]))
      (ui/on-error {:status 404}))))

;; ------------------------------------------------------------------ files

(defn stored-document [{:keys [biff.xtdb/node] :as ctx}]
  (let [id (routes/stored-document-id ctx)]
    (if-let [{:document/keys [media-type filename]} (document/get-document node id)]
      (try
        {:status  200
         :headers (cond-> {"content-type" (or media-type "application/octet-stream")
                           "cache-control" "private, max-age=31536000, immutable"}
                    filename (assoc "content-disposition"
                                    (str "inline; filename=\"" (str/replace filename #"[\"\\\r\n]" "") "\"")))
         :body    (java.io.ByteArrayInputStream.
                   (document/content (:trustblocks/document-root ctx document/default-root) id))}
        (catch clojure.lang.ExceptionInfo e
          (if (#{:document-missing :document-corrupt} (:trustblocks/error (ex-data e)))
            {:status 410 :headers {"content-type" "text/plain"}
             :body (str "The stored file for " id " is " (if (= :document-missing (:trustblocks/error (ex-data e)))
                                                           "missing" "corrupt -- it no longer matches its hash")
                        ", so it is not served.")}
            (throw e))))
      (ui/on-error {:status 404}))))

(def module
  {:biff.ring/routes
   [["" {:middleware [mid/wrap-signed-in]}
     [(routes/matters) {:get list-page}]
     [(routes/matter) {:get show-page}]
     [(routes/stored-document) {:get stored-document}]]]})
