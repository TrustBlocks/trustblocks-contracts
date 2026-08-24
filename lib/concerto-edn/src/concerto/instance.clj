(ns concerto.instance
  "Concerto JSON instances <-> neutral EDN.

  The neutral form preserves Concerto's property spelling exactly, as keywords:
  `{:$class \"...\" :businessDays 15}`. Both `:$class` and `:businessDays` are
  legal EDN keywords that print and read back identically, so the conversion is
  lossless and needs no mapping table.

  That matters more than it looks. Every reverse-mapping bug we have hit --
  `vechileID` coming back as `vechileId`, `INITIAL_DUE` as `initialDue` --
  existed only because a storage layer forced kebab-case on us and the original
  spelling then had to be recovered from the model. At this layer there is
  nothing to recover, so an adapter's key mangling stays the adapter's problem."
  (:require [clojure.walk :as walk]))

(defn json->edn
  "Concerto JSON instance (string keys) -> neutral EDN. Recurses into nested
  concepts and arrays, which carry their own `$class`."
  [instance]
  (walk/keywordize-keys instance))

(defn edn->json
  "Neutral EDN instance -> Concerto JSON shape (string keys)."
  [instance]
  (walk/stringify-keys instance))

(defn class-of [instance] (:$class instance))

(defn identifier-of
  "Concerto's `$identifier` mirrors the `identified by` field, so prefer it and
  fall back to the declared identity field for models that predate it."
  [instance & {:keys [identity-field]}]
  (or (:$identifier instance)
      (when identity-field (get instance (keyword identity-field)))))
