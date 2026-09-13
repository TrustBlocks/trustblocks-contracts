(ns accord-probe
  "Probe the open Accord agenda items empirically, using real templates.

    bin/clj -M:xtdb-jvm-opts -i dev/accord_probe.clj"
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [com.trustblocks.xtdb.store :as store]
            [xtdb.node :as xt.node]))

(def lib (str (System/getProperty "user.home")
              "/Code/Accord/cicero-template-library/src/"))

(defn- deep-classes
  "Every $class tag in an instance, with its path -- so we can tell whether
  nested concepts keep their own tag through a round trip."
  ([m] (deep-classes m []))
  ([m path]
   (cond
     (map? m)    (concat (when-let [c (get m "$class")] [[path c]])
                         (mapcat (fn [[k v]] (deep-classes v (conj path k))) m))
     (vector? m) (apply concat (map-indexed (fn [i x] (deep-classes x (conj path i))) m))
     :else       nil)))

(defn probe [template]
  (let [dir   (str lib template)
        node  (xt.node/start-node)
        ctos  (store/model-dir-files (str dir "/model"))
        samp  (json/parse-string (slurp (str dir "/sample.json")))]
    (try
      (let [reg (store/load-registry ctos)
            _   (store/put-instance! node reg samp)
            rt  (store/get-instance node (get samp "$identifier"))
            orig-cls (set (deep-classes samp))
            rt-cls   (set (deep-classes rt))]
        (println "\n============================" template)
        (println "$class tags  original:" (count orig-cls) " round-trip:" (count rt-cls))
        (println "nested tags preserved:" (= orig-cls rt-cls))
        (when-not (= orig-cls rt-cls)
          (println "  lost:" (pr-str (sort (map second (remove rt-cls orig-cls))))))
        (doseq [[k v] (sort samp)
                :when (and (string? v) (re-find #"^\d{4}-\d{2}-\d{2}T" v))]
          (let [back (get rt k)]
            (printf "  %-26s %s\n      round-trip %s   %s\n"
                    k v back (if (= v back) "IDENTICAL" "*** CHANGED ***"))))
        (doseq [[k v] (sort samp)
                :when (and (map? v) (str/includes? (str (get v "$class")) "Monetary"))]
          (printf "  %-26s %s  -> round-trip %s\n" k (pr-str v) (pr-str (get rt k))))
        (println "  whole instance identical:" (= samp rt)))
      (catch Exception e
        (println "\n============================" template)
        (println "  FAILED:" (ex-message e) (pr-str (ex-data e))))
      (finally (.close node)))))

(doseq [t ["interest-rate-swap" "fragile-goods"]] (probe t))
