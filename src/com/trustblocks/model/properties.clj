(ns com.trustblocks.model.properties
  "A Concerto class's properties in the order a reader expects: flattened
  over its supertype chain, parent first."
  (:require [com.trustblocks.concerto.metamodel :as mm]))

(defn own-properties
  "This class's properties, flattened over its supertype chain, parent first,
  a child's redeclaration overriding its parent's at the parent's position.
  Mirrors concerto-clj's own flattening rule for compiling Malli schemas --
  tracked explicitly rather than with an array-map, which silently becomes an
  unordered hash-map once it passes eight entries.

  Public because it's also the ordered field list a page reaches for when
  rendering an instance or a $class's schema -- the same declaration order
  a lawyer reading the source contract would expect, not JSON key order
  (unspecified) or alphabetical (meaningless)."
  [reg fqn]
  (let [props (mapcat #(:properties (:declaration (get reg %)))
                      (reverse (mm/super-chain reg fqn)))]
    (:order
     (reduce (fn [{:keys [idx order] :as acc} prop]
               (let [k (:name prop)]
                 (if-let [i (idx k)]
                   (assoc-in acc [:order i] prop)
                   (-> acc
                       (assoc-in [:idx k] (count order))
                       (update :order conj prop)))))
             {:idx {} :order []}
             props))))
