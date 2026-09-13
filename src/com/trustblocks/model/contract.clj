(ns com.trustblocks.model.contract
  "Composes contract reads into a biff.graph graph, so a page can ask for a
  contract's data, current state, and execution history in one query instead
  of three separate calls into com.trustblocks.xtdb.store/execute.

  This is the cheap version of 'contracts are a graph': each Concerto
  instance's shape depends on its $class, so these resolvers stay
  coarse-grained. The output is declared as a [:*] wildcard join rather than
  an enumerated list of attributes -- the leaves are opaque Concerto maps
  handed back verbatim, not individually resolved fields. A resolver
  generator driven by the Concerto model (one resolver per declared property,
  relationships as joins) would give real field-level queries; that's a
  bigger, separate decision.

  :contract/state and :contract/history are legitimately absent before a
  contract's first `trigger!`. Query them as optional joins -- note the `[:?
  ...]` wraps the *key*, not the whole map entry -- so a fresh contract isn't
  a graph error:

    (biff.graph/query ctx {:contract/id id}
                      [{:contract/data [:*]}
                       {[:? :contract/state]   [:*]}
                       {[:? :contract/history] [:*]}])"
  (:require [com.biffweb.graph :refer [defresolver]]
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
