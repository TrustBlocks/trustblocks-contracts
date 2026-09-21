(ns com.trustblocks.app.auth-test
  "Covers the one thing in com.trustblocks.app.auth that can silently do the
  wrong thing.

  An XTQL `from` whose binding pattern doesn't actually constrain anything
  returns the whole table rather than an error, so get-user-id would answer
  every lookup with whichever user happened to be stored first. Signin would
  still work -- you would just be signed in as someone else. Nothing about
  the shape of the query says which of the two it is, hence a test."
  (:require [clojure.test :refer [deftest is testing]]
            [com.trustblocks.app.auth :as auth]
            [xtdb.node :as xt.node]))

(deftest get-user-id-finds-only-the-matching-user
  (with-open [node (xt.node/start-node)]
    (let [ctx {:biff.xtdb/node node}]
      (testing "nobody stored yet"
        (is (nil? (auth/get-user-id ctx "ada@example.com"))))

      (let [ada (auth/create-user! ctx {:email "ada@example.com"})]
        (testing "the user just created is found by email"
          (is (= ada (auth/get-user-id ctx "ada@example.com"))))

        (testing "a second user does not shadow or get confused with the first"
          (let [grace (auth/create-user! ctx {:email "grace@example.com"})]
            (is (not= ada grace))
            (is (= ada (auth/get-user-id ctx "ada@example.com")))
            (is (= grace (auth/get-user-id ctx "grace@example.com")))))

        (testing "an email with no user is nil, not the first row in the table"
          ;; Also what makes a repeat signin reuse the account: the module
          ;; only calls create-user when get-user-id came back nil.
          (is (nil? (auth/get-user-id ctx "nobody@example.com"))))))))
