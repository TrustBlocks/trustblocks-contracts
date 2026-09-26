(ns com.trustblocks.app.matters
  "Matters, read-only: each matter's terms, its documents and where each
  stands in its lifecycle, and the signed record of how it got there --
  every designation, receipt and attestation naming the matter, with who
  signed it, under what authority, and the stored file it names.

  Also serves those stored files (/documents/:id), re-hashed on every read
  (com.trustblocks.xtdb.document/content): a file that no longer matches
  the hash a credential names is refused, never served.

  Like the contract pages, everything here is behind signin. Nothing here
  writes; a matter moves only through com.trustblocks.xtdb.advance."
  (:require [clojure.string :as str]
            [com.trustblocks.lib.middleware :as mid]
            [com.trustblocks.lib.ui :as ui]
            [com.trustblocks.routes :as routes]
            [com.trustblocks.xtdb.certification :as cert]
            [com.trustblocks.xtdb.document :as document]
            [com.trustblocks.xtdb.execute :as execute]
            [com.trustblocks.xtdb.identity :as identity]
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
   "RETURNED"  ["bg-slate-100 text-slate-600 ring-slate-200"]
   "OVERDUE"   ["bg-rose-50 text-rose-700 ring-rose-200"]})

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

(defn show-page [{:keys [biff.xtdb/node] :as ctx}]
  (let [id (routes/matter-id ctx)]
    (if-let [{:matter/keys [name package terms]} (matter/get-matter node id)]
      (let [record (sort-by #(get % "valid-from") (cert/matter-record node id))]
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
              (str " · " c))]]
          (documents node id)
          (designations node record)
          [:section {:class section}
           [:h2 {:class ["mb-1 text-lg font-semibold text-slate-950"]} (str "Signed Record (" (count record) ")")]
           [:p {:class ["mb-3 text-sm text-slate-500"]}
            "Every credential naming this matter, by the date of the act, each verified against its signer's key."]
           [:ol {:class ["divide-y divide-slate-100"]} (map #(record-entry node %) record)]]]))
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
