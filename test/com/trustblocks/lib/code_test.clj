(ns com.trustblocks.lib.code-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.trustblocks.lib.code :as code]
            [dev.onionpancakes.chassis.core :as chassis]))

(def ^:private cto
  "namespace test@1.0.0

/**
 * A doc comment
 * over lines.
 */
@template
asset Thing identified by id {
  o String id
  o Double amount default=1.5 optional
  --> Party owner // who owns it
}")

(defn- html [] (chassis/html (code/cto-listing cto)))

(defn- text-of [h] (str/replace h #"<[^>]+>" ""))

(deftest a-cto-listing
  (let [h (html)]
    (testing "every line, numbered, its text exactly as written -- indentation included"
      (is (= 12 (count (re-seq #"id=\"L\d+\"" h))))
      (is (str/includes? (text-of h) "  o String id"))
      (is (str/includes? (text-of h) "  --&gt; Party owner // who owns it"))
      (is (re-find #"whitespace-pre" h)))
    (testing "highlighted by kind"
      (is (re-find #"text-sky-300[^>]*>namespace<" h))
      (is (re-find #"text-sky-300[^>]*>o<" h))
      (is (re-find #"text-sky-300[^>]*>--&gt;<" h))
      (is (re-find #"text-amber-200[^>]*>String<" h))
      (is (re-find #"text-fuchsia-300[^>]*>@template<" h))
      (is (re-find #"text-violet-300[^>]*>1.5<" h))
      (is (re-find #"text-emerald-300[^>]*>// who owns it<" h)))
    (testing "a /** */ comment is a comment on every line it spans"
      (is (re-find #"text-emerald-300[^>]*> \* over lines.<" h))
      (is (re-find #"text-emerald-300[^>]*> \*/<" h)))))
