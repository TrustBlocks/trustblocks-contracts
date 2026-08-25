(ns com.trustblocks.contract
  "Cross-contract querying.

  Concerto templates do not model parties as parties: across the whole Accord
  library not one declaration uses a Party or Organization relationship. A
  signatory is a bare String whose field name differs per template -- licensee,
  licensor, maker, lender, shipper, receiver. So a question like \"what did Tom
  Brooke sign\" cannot be answered from the models alone.

  We therefore derive a normalized projection at ingest and store it alongside
  the instance under namespaced keys, which `<-xtdb` excludes from the Concerto
  reconstruction. XTDB being schemaless is what lets us bolt this on without
  touching the contract data.

  The role map is deliberately explicit. There is no semantic marker to infer
  it from, and guessing which String is a person would be worse than declaring
  it."
  (:require [com.trustblocks.xtdb.keys :as k]
            [com.trustblocks.concerto.metamodel :as mm]
            [com.trustblocks.xtdb.store :as store]
            [xtdb.api :as xt]))

(def party-table
  "Derived join table: one row per (contract, party)."
  :contract-party)

(def party-roles
  "$class -> {concerto-field role}. Extend as templates are onboarded."
  {"org.accordproject.copyrightlicense@0.2.0.TemplateModel"
   {"licensee" :licensee "licensor" :licensor}

   "org.accordproject.promissorynote@0.2.0.TemplateModel"
   {"maker" :maker "lender" :lender}

   "org.accordproject.acceptanceofdelivery@0.1.0.TemplateModel"
   {"shipper" :shipper "receiver" :receiver}})

(def date-fields
  "$class -> the Concerto field holding the contract's operative date."
  {"org.accordproject.copyrightlicense@0.2.0.TemplateModel"   "effectiveDate"
   "org.accordproject.promissorynote@0.2.0.TemplateModel"     "date"})

(defn projection
  "Derive queryable metadata from a Concerto instance."
  [instance]
  (let [fqn   (get instance "$class")
        roles (get party-roles fqn)
        date  (get instance (get date-fields fqn))]
    (cond-> {:contract/class fqn}
      (seq roles)
      (assoc :party/names (vec (distinct (keep #(get instance %) (keys roles))))
             :party/roles (into {} (keep (fn [[f r]]
                                           (when-let [v (get instance f)]
                                             [r v]))
                                         roles)))
      date (assoc :contract/date date))))

(defn put-contract!
  "Store a filled contract with its derived projection.

  `valid-from` sets XTDB's valid time -- when the contract took effect in the
  world, as distinct from when we recorded it. That distinction is what makes
  \"which contracts existed on date X\" answerable, and it is the reason to use
  a bitemporal store for contracts at all."
  [node reg instance & {:keys [valid-from table] :or {table store/contract-table}}]
  (let [reg (or reg (store/registry-from-xtdb node))
        fqn (get instance "$class")]
    (when-let [errs (store/validate reg instance)]
      (throw (ex-info "Contract failed validation" {:$class fqn :errors errs})))
    (let [doc  (merge (k/instance->doc
                       instance
                       :identity-field (mm/identity-field reg fqn))
                      (projection instance))
          opts (cond-> {:into table} valid-from (assoc :valid-from valid-from))
          ;; One row per (contract, party). A join table rather than an array
          ;; column: it indexes, and it keeps the role attached to the name.
          party-rows
          (for [[role nm] (:party/roles (projection instance))]
            [:put-docs (cond-> {:into party-table}
                         valid-from (assoc :valid-from valid-from))
             {:xt/id        (str (:xt/id doc) "#" (name role))
              :party/name   nm
              :party/role   role
              :contract/id  (:xt/id doc)
              :contract/class fqn}])]
      (xt/execute-tx node (into [[:put-docs opts doc]] party-rows))
      {:xt/id (:xt/id doc) :$class fqn})))

;; ---------------------------------------------------------------- queries

(defn signed-by
  "Every contract naming `person` as a party, with the role they held."
  [node person]
  (xt/q node (list 'from party-table [{:party/name person} (symbol "*")])))

(defn as-of
  "Contracts whose records were valid at `instant` -- XTDB valid time, not the
  contract's own date field. Answers \"what did our books look like then\"."
  [node instant]
  (xt/q node
        (list 'from store/contract-table
              {:bind [(symbol "*")]
               :for-valid-time (list 'at instant)})))
