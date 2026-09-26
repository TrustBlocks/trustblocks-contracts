(ns com.trustblocks.app.certify-test
  "Exercises the handler directly, the same way
  com.trustblocks.app.auth-test does -- a plain ctx map and a function
  call, no HTTP server. Found a real bug the first time this ran for
  real (java.time.Instant does not support a calendar Period the way
  ZonedDateTime does; see com.trustblocks.app.certify/no-natural-expiry),
  caught only by an actual /certify POST against the running app, not by
  anything here -- these tests exist so the next such bug doesn't need
  that."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.trustblocks.app.auth :as auth]
            [com.trustblocks.app.certify :as certify]
            [com.trustblocks.xtdb.certification :as cert]
            [com.trustblocks.xtdb.identity :as identity]
            [com.trustblocks.xtdb.store :as store]
            [manager-employment-demo :as me]
            [xtdb.node :as xt.node])
  (:import [java.time Instant]))

(def ^:private contract-id me/contract-id)
(def ^:private clerk-email "clerk@china-grove.example.gov")

(defn- provision-clerk! [node]
  (let [town  (identity/provision-town! node {:slug "china-grove" :name "China Grove"})
        clerk (identity/provision-officeholder! node {:email clerk-email :name "Jane Clerk"
                                                      :office "Town Clerk"})]
    (cert/grant-office! node (:xt/id town)
                        {:officeholder-did (:xt/id clerk) :name "Jane Clerk" :office "Town Clerk"
                         :authority ["CERTIFY_MINUTES"]
                         :valid-from (Instant/parse "2025-01-01T00:00:00Z")
                         :valid-until (Instant/parse "2030-01-01T00:00:00Z")})))

(defn- ctx-for [node email params]
  (let [uid (auth/create-user! {:biff.xtdb/node node} {:email email})]
    {:biff.xtdb/node node
     :uri     (str "/contracts/" contract-id "/certify")
     :session {:uid uid}
     :params  params}))

(deftest certify-redirects-to-the-contract-page-either-way
  (with-open [node (xt.node/start-node)]
    ;; certify runs execute/trigger! against contract-id -- see
    ;; com.trustblocks.xtdb.certification's own docstring -- so the real
    ;; contract needs to be on file and already Board-approved, which the
    ;; Clerk certifies.
    (me/load! node)
    (store/put-model! node "model/identity.cto")
    (provision-clerk! node)
    (me/approve! node clerk-email)

    (testing "a missing field redirects with a certify-error, not a 500"
      (let [ctx (ctx-for node clerk-email {:documentHash "h" :validFrom "2026-03-04T19:00"})
            {:keys [status headers]} (certify/certify ctx)]
        (is (= 303 status))
        (is (str/includes? (get headers "location") "certify-error="))))

    (testing "an email with no officeholder identity is refused, not a 500"
      (let [ctx (ctx-for node "nobody@example.gov"
                         {:statement "x" :documentHash "h" :validFrom "2026-03-04T19:00"})
            {:keys [status headers]} (certify/certify ctx)]
        (is (= 303 status))
        (is (str/includes? (get headers "location") "certify-error=No+officeholder"))))

    (testing "a valid certification redirects cleanly and is actually recorded"
      (let [ctx (ctx-for node clerk-email
                         {:statement "The council voted 4-1" :documentHash "sha256:vote"
                          :validFrom "2026-03-04T19:00"})
            {:keys [status headers]} (certify/certify ctx)]
        (is (= 303 status))
        (is (= (str "/contracts/" contract-id) (get headers "location")))
        ;; The Board approval, then this.
        (is (= 2 (count (cert/attestations-for-contract node contract-id))))))

    (testing "a second certification can name the first as evidence"
      (let [[_ first-jwt] (map #(get % "jwt") (cert/attestations-for-contract node contract-id))
            ctx (ctx-for node clerk-email
                         {:statement "Minutes approved" :documentHash "sha256:minutes"
                          :validFrom "2026-04-01T19:00" :evidence first-jwt})
            {:keys [status]} (certify/certify ctx)]
        (is (= 303 status))
        (let [[_ _ minutes] (cert/attestations-for-contract node contract-id)]
          (is (= first-jwt (get minutes "evidence"))))))))
