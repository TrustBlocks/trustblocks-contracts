(ns com.trustblocks.xtdb.identity-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.trustblocks.xtdb.identity :as identity]
            [xtdb.node :as xt.node]))

(deftest provisions-a-town-with-a-path-based-did-web
  (with-open [node (xt.node/start-node)]
    (let [town (identity/provision-town! node {:slug "china-grove" :name "China Grove"})]
      (is (= "did:web:trustblocks.com:towns:china-grove" (:xt/id town)))
      (is (= :town (:identity/kind town)))
      (is (= 32 (alength (:identity/public-key town))))
      (is (= 32 (alength (:identity/private-key town)))))))

(deftest provisions-an-officeholder-with-a-did-key
  (with-open [node (xt.node/start-node)]
    (let [clerk (identity/provision-officeholder! node {:email "clerk@china-grove.example.gov"
                                                        :name "Jane Clerk" :office "Town Clerk"})]
      (is (.startsWith ^String (:xt/id clerk) "did:key:z"))
      (is (= :officeholder (:identity/kind clerk)))
      (is (= "clerk@china-grove.example.gov" (:identity/email clerk))))))

(deftest by-did-and-by-email-find-what-was-provisioned
  (with-open [node (xt.node/start-node)]
    (let [town (identity/provision-town! node {:slug "china-grove" :name "China Grove"})
          clerk (identity/provision-officeholder! node {:email "clerk@china-grove.example.gov"
                                                        :name "Jane Clerk" :office "Town Clerk"})]
      (testing "by-did"
        (is (= (:xt/id town) (:xt/id (identity/by-did node (:xt/id town)))))
        (is (= (:xt/id clerk) (:xt/id (identity/by-did node (:xt/id clerk))))))
      (testing "by-email finds the officeholder, not the town"
        (is (= (:xt/id clerk) (:xt/id (identity/by-email node "clerk@china-grove.example.gov")))))
      (testing "an unknown did or email is nil, not an error"
        (is (nil? (identity/by-did node "did:key:zNoSuchThing")))
        (is (nil? (identity/by-email node "nobody@example.gov")))))))

(deftest keypair-is-ready-for-interop-and-credential
  (with-open [node (xt.node/start-node)]
    (let [clerk (identity/provision-officeholder! node {:email "clerk@china-grove.example.gov"
                                                        :name "Jane Clerk" :office "Town Clerk"})
          {:keys [public private]} (identity/keypair clerk)]
      (is (= (:identity/public-key clerk) public))
      (is (= (:identity/private-key clerk) private)))))
