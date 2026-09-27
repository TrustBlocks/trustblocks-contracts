(ns xtdb-unindexed-tx-repro
  "XTDB 2.1.0 on its own, no Trustblocks code: sixteen writers race to
  increment one row, each guarding its write with ASSERT (optimistic
  concurrency) and reading again when the guard aborts its transaction.
  Some of those reads throw :xtdb/unindexed-tx -- \"system-time ... is
  after the latest completed tx\" -- instead of waiting for the node to
  catch up. No data is lost (final n = committed); the reads just fail.

  com.trustblocks.xtdb.execute/trigger! waits and reads again when this
  happens (see its unindexed?). This is kept to re-check new XTDB releases
  and for the upstream report.

    bin/clj -M:xtdb-jvm-opts -i dev/xtdb_unindexed_tx_repro.clj

  Typical: committed: 396 unindexed-tx errors: 4 final n: 396"
  (:require [xtdb.api :as xt]
            [xtdb.node :as xt.node]))

(def unindexed (atom 0))
(def ok (atom 0))
(with-open [node (xt.node/start-node)]
  (xt/execute-tx node [[:put-docs :counter {:xt/id 1 :n 0}]])
  (->> (range 16)
       (mapv (fn [_]
               (future
                 (dotimes [_ 25]
                   (try
                     (loop []
                       (let [{:keys [n]} (first (xt/q node '(from :counter [{:xt/id 1} n])))
                             res (try (xt/execute-tx node [[:sql "ASSERT (SELECT n FROM counter WHERE _id = 1) = ?" [n]]
                                                           [:put-docs :counter {:xt/id 1 :n (inc n)}]])
                                      (catch Exception e (if (= :xtdb/assert-failed (:xtdb.error/code (ex-data e))) ::retry (throw e))))]
                         (if (= ::retry res) (recur) (swap! ok inc))))
                     (catch Exception e
                       (if (= :xtdb/unindexed-tx (:xtdb.error/code (ex-data e)))
                         (swap! unindexed inc)
                         (throw e))))))))
       (run! deref))
  (println "committed:" @ok "unindexed-tx errors:" @unindexed
           "final n:" (:n (first (xt/q node '(from :counter [{:xt/id 1} n]))))))
