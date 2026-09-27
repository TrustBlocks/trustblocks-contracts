(ns com.trustblocks.xtdb.note
  "Notes on a contract: context an officeholder records on its timeline --
  \"the Contractor appears to be in trouble\" -- that is not part of the
  contract. A running contract cannot be changed; a note changes nothing,
  it records what someone knew.

  Every note has two times, and both are the point:

    about      the day it concerns -- its place on the timeline, and its
               XTDB valid time;
    recorded   when it was written -- XTDB's system time, set by the
               database on commit, which no one can backdate.

  So a note recorded in March about March is evidence, in June, that the
  Town knew in March: the record of when it was written is the database's,
  not the author's. A note can be about an earlier day than the one it is
  recorded on (\"at the April 3 site visit ...\"), and the timeline shows
  both."
  (:require [clojure.string :as str]
            [xtdb.api :as xt]))

(def note-table :note)

(defn add!
  "Record `text` by `author` (the signed-in email) on `contract`, about
  `about` (an Instant; default now). Returns the note's id."
  [node {:keys [contract author text about]}]
  (when (str/blank? text)
    (throw (ex-info "A note needs some text" {:trustblocks/error :invalid-note})))
  (let [id (random-uuid)]
    (xt/execute-tx node [[:put-docs (cond-> {:into note-table}
                                      about (assoc :valid-from about))
                          {:xt/id id :contract contract :author author :text text}]])
    id))

(defn for-contract
  "Every note on `contract`, oldest first by what it is about --
  {:id :author :text :about :recorded}, the two times as Instants."
  [node contract]
  (->> (xt/q node (list 'from note-table
                        {:for-valid-time  :all-time
                         :for-system-time :all-time
                         :bind [{:contract contract} 'xt/id 'author 'text
                                'xt/valid-from 'xt/system-from]}))
       (map (fn [r]
              {:id       (:xt/id r)
               :author   (:author r)
               :text     (:text r)
               :about    (.toInstant ^java.time.ZonedDateTime (:xt/valid-from r))
               :recorded (.toInstant ^java.time.ZonedDateTime (:xt/system-from r))}))
       ;; A note is written once; one row per note.
       (group-by :id)
       vals
       (map #(apply min-key (comp inst-ms :recorded) %))
       (sort-by :about)
       vec))
