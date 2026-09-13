(ns com.trustblocks.model.contract
  "Composes contract reads into a biff.graph graph, so a page can ask for a
  contract's data, current state, and execution history in one query instead
  of three separate calls into com.trustblocks.xtdb.store/execute.

  These three resolvers are coarse-grained on purpose: each Concerto
  instance's shape depends on its $class, and biff.graph has no idea what
  properties a $class carries, so the output is declared as a [:*] wildcard
  join and handed back as an opaque Concerto map. For real field-level
  queries -- :snow-removal/contractor rather than the whole :contract/data
  blob -- see graph-ctx below and com.trustblocks.model.graph, which
  generates a resolver per property directly from the Concerto model.

  :contract/state and :contract/history are legitimately absent before a
  contract's first `trigger!`. Query them as optional joins -- note the `[:?
  ...]` wraps the *key*, not the whole map entry -- so a fresh contract isn't
  a graph error:

    (biff.graph/query ctx {:contract/id id}
                      [{:contract/data [:*]}
                       {[:? :contract/state]   [:*]}
                       {[:? :contract/history] [:*]}])"
  (:require [com.biffweb.graph :as biff.graph :refer [defresolver]]
            [com.trustblocks.model.graph :as model.graph]
            [com.trustblocks.xtdb.execute :as execute]
            [com.trustblocks.xtdb.store :as store]))

(defresolver contract
  {:input  [:contract/id]
   :output [{:contract/data [:*]}]}
  [{:keys [biff.xtdb/node]} {:contract/keys [id]}]
  {:contract/data (store/get-instance node id)})

(defresolver contract-state
  {:input  [:contract/id]
   :output [{:contract/state [:*]}]}
  [{:keys [biff.xtdb/node]} {:contract/keys [id]}]
  {:contract/state (execute/current-state node id)})

(defresolver contract-history
  {:input  [:contract/id]
   :output [{:contract/history [:*]}]}
  [{:keys [biff.xtdb/node]} {:contract/keys [id]}]
  {:contract/history (execute/history node id)})

(def module
  {:biff.graph/resolvers [contract contract-state contract-history]})

(defn graph-ctx
  "A fresh biff.graph ctx for field-level queries: the three resolvers above,
  plus one generated resolver per identity-bearing $class in the *current*
  registry (com.trustblocks.model.graph/resolvers-for-registry).

  biff.graph normally assumes a resolver set fixed for the system's
  lifetime, built once from :biff.graph/resolvers at startup -- that's what
  `module` above and (biff.graph/query system ...) still give you, for free,
  for the coarse-grained resolvers. It doesn't hold for the generated half:
  which $classes exist is discovered from whatever models have been ingested
  into XTDB, at any time, with deliberately no filesystem dependency -- the
  same 'schema lives beside the data' rule com.trustblocks.xtdb.store
  follows. So this rebuilds that half from the live registry on every call
  rather than once at boot.

  Cheap for a handful of templates. If it becomes a hot path, memoize on the
  registry's own content instead of reaching for a cache-invalidation scheme
  -- the registry is already immutable data once loaded.

  Dissociates :biff.graph/get-ctx from the result -- `query` re-derives and
  merges that itself when present (see biff.graph.impl.query/query), which
  would otherwise clobber the freshly generated resolvers right back down to
  just the static three from `module`.

    (biff.graph/query (graph-ctx system) {:contract/id id}
                      [:snow-removal/contractor
                       {:snow-removal/rate-per-visit [:money/amount :money/currency]}])"
  [{:keys [biff.xtdb/node] :as ctx}]
  (let [reg (store/registry-from-xtdb node)]
    (-> ctx
        (dissoc :biff.graph/get-ctx)
        (merge (biff.graph/new-ctx
                (into [contract contract-state contract-history]
                      (model.graph/resolvers-for-registry reg store/get-instance)))))))
