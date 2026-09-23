(ns com.trustblocks.app.authorizations
  "Read-only ledger of email-linked officeholders and their signed DID
  credentials. This is operational visibility, not a provisioning surface:
  identities and OfficeGrants are still created through the documented
  runbook/REPL path so key generation and authority assignment remain an
  explicit administrative act."
  (:require [clojure.string :as str]
            [com.trustblocks.lib.middleware :as mid]
            [com.trustblocks.lib.ui :as ui]
            [com.trustblocks.routes :as routes]
            [com.trustblocks.xtdb.certification :as certification]))

(defn- compact-did [did]
  (if (< 38 (count did))
    (str (subs did 0 24) "…" (subs did (- (count did) 10)))
    did))

(defn- authority-label [authority]
  (-> authority
      str/lower-case
      (str/replace "_" " ")
      str/capitalize))

(defn- status-badge [{:keys [status verified]}]
  (let [[label classes]
        (cond
          (not verified) ["Verification failed" ["bg-red-50 text-red-700 ring-red-200"]]
          (= status :active) ["Active" ["bg-emerald-50 text-emerald-700 ring-emerald-200"]]
          (= status :not-yet-valid) ["Not yet valid" ["bg-amber-50 text-amber-700 ring-amber-200"]]
          :else ["Expired" ["bg-slate-100 text-slate-600 ring-slate-200"]])]
    [:span {:class (into ["rounded-full px-2.5 py-1 text-xs font-semibold ring-1 ring-inset"] classes)}
     label]))

(defn- grant-card [{:keys [issuer authority valid-from valid-until error] :as grant}]
  [:div {:class ["rounded-lg border border-slate-200 bg-slate-50 p-4"]}
   [:div {:class ["flex flex-wrap items-center justify-between gap-3"]}
    [:p {:class ["text-xs font-semibold uppercase tracking-wider text-slate-500"]} "OfficeGrant VC"]
    (status-badge grant)]
   [:div {:class ["mt-3 flex flex-wrap gap-2"]}
    (if (seq authority)
      (for [item authority]
        [:span {:class ["rounded-md bg-white px-2.5 py-1 text-xs font-medium text-slate-700 ring-1 ring-slate-200"]}
         (authority-label item)])
      [:span {:class ["text-sm text-slate-400"]} "No authority claims decoded"])]
   [:dl {:class ["mt-4 grid gap-3 text-xs text-slate-600 sm:grid-cols-3"]}
    [:div [:dt {:class ["font-semibold text-slate-500"]} "Issuer"]
     [:dd {:class ["mt-1 break-all font-mono"]} issuer]]
    [:div [:dt {:class ["font-semibold text-slate-500"]} "Valid from"]
     [:dd {:class ["mt-1"]} (str valid-from)]]
    [:div [:dt {:class ["font-semibold text-slate-500"]} "Valid until"]
     [:dd {:class ["mt-1"]} (str valid-until)]]]
   (when error
     [:p {:class ["mt-3 text-sm font-medium text-red-700"]} error])])

(defn- officeholder-card [{:keys [did name email office grants]}]
  [:article {:class ["rounded-xl border border-slate-200 bg-white p-5 shadow-sm"]}
   [:div {:class ["flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between"]}
    [:div
     [:h2 {:class ["text-lg font-semibold text-slate-950"]} name]
     [:p {:class ["text-sm text-slate-600"]} office]
     [:a {:href (str "mailto:" email) :class ["mt-1 inline-block text-sm font-medium text-blue-700 hover:underline"]}
      email]]
    [:div {:class ["rounded-lg bg-slate-100 px-3 py-2 text-right"]}
     [:p {:class ["text-[0.65rem] font-semibold uppercase tracking-wider text-slate-500"]} "did:key"]
     [:p {:title did :class ["mt-0.5 font-mono text-xs text-slate-700"]} (compact-did did)]]]
   [:div {:class ["mt-5 space-y-3"]}
    (if (seq grants)
      (map grant-card grants)
      [:div {:class ["rounded-lg border border-dashed border-slate-300 p-4 text-sm text-slate-500"]}
       "Identity provisioned; no OfficeGrant credential is on file."])]])

(defn list-page [{:keys [biff.xtdb/node]}]
  (let [authorizations (certification/signature-authorizations node)]
    (ui/page
     {:ui/title "Signature Authorizations — Trustblocks"}
     (ui/workspace-header :authorizations)
     [:main {:class ["mx-auto w-full max-w-6xl px-6 py-8"]}
      [:div {:class ["mb-7"]}
       [:p {:class ["text-sm font-semibold uppercase tracking-widest text-blue-700"]} "DID Credential Registry"]
       [:h1 {:class ["mt-1 text-3xl font-semibold tracking-tight text-slate-950"]} "Signature Authorizations"]
       [:p {:class ["mt-2 max-w-3xl text-sm leading-6 text-slate-600"]}
        "Email-linked municipal identities and the cryptographically signed OfficeGrant credentials that define when and how each person may act. This page never exposes custodial private keys."]]
      (if (empty? authorizations)
        [:div {:class ["rounded-xl border border-dashed border-slate-300 bg-white p-10 text-center text-slate-500"]}
         "No officeholder identities are provisioned yet. See docs/operator-runbook.md."]
        [:div {:class ["grid gap-5 lg:grid-cols-2"]}
         (map officeholder-card authorizations)])])))

(def module
  {:biff.ring/routes
   [["" {:middleware [mid/wrap-signed-in]}
     [(routes/authorizations) {:get list-page}]]]})
