#!/usr/bin/env bb
(ns cto2edn
  "Port a Concerto contract to EDN.

    bb script/cto2edn.clj MODEL [options]

  MODEL is a .cto file or a directory of them (an Accord template's model/
  folder vendors its imports, so the directory resolves cross-namespace
  inheritance).

    --registry            the declaration registry as EDN          [default]
    --malli FQN           the Malli schema for one declaration, as EDN
    --instance FILE.json  a Concerto JSON instance as neutral EDN
    --validate FILE.json  validate an instance against its own $class
    --list                every declaration the model defines
    --cli PATH            path to the concerto CLI
    -o FILE               write to FILE instead of stdout

  Needs Accord's CLI on $PATH or in $CONCERTO_CLI:
    npm i -g @accordproject/concerto-cli"
  (:require [cheshire.core :as json]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [concerto.cto :as cto]
            [concerto.instance :as inst]
            [concerto.malli :as cm]
            [malli.core :as m]
            [malli.error :as me]))

(defn- parse-args [args]
  (loop [[a & more] args, opts {}, positional []]
    (cond
      (nil? a) (assoc opts :model (first positional))
      (#{"--malli" "--instance" "--validate" "--cli" "-o"} a)
      (recur (rest more) (assoc opts (keyword (str/replace a #"^-+" "")) (first more)) positional)
      (#{"--registry" "--list"} a)
      (recur more (assoc opts (keyword (subs a 2)) true) positional)
      (#{"-h" "--help"} a) (assoc opts :help true)
      :else (recur more opts (conj positional a)))))

(defn- emit [opts x]
  (let [s (with-out-str (pp/pprint x))]
    (if-let [f (:o opts)]
      (do (spit f s) (println "wrote" f (str "(" (count s) " bytes)")))
      (print s))))

(defn -main [& args]
  (let [{:keys [model help cli] :as opts} (parse-args args)]
    (when (or help (nil? model))
      (println (:doc (meta (the-ns 'cto2edn))))
      (System/exit (if help 0 1)))

    (let [reg (cto/load-registry model :cli cli)]
      (cond
        (:list opts)
        (doseq [[fqn {:keys [declaration]}] (sort-by key reg)]
          (printf "%-70s %s\n" fqn
                  (-> (:$class declaration) (str/split #"\.") last)))

        (:malli opts)
        (emit opts (cm/->edn reg (:malli opts)))

        (:instance opts)
        (emit opts (inst/json->edn (json/parse-string (slurp (:instance opts)))))

        (:validate opts)
        (let [instance (inst/json->edn (json/parse-string (slurp (:validate opts))))
              fqn      (inst/class-of instance)
              schema   (cm/->schema reg fqn)]
          (if (m/validate schema instance)
            (println "VALID  " fqn)
            (do (println "INVALID" fqn)
                (pp/pprint (me/humanize (m/explain schema instance)))
                (System/exit 1))))

        :else (emit opts reg)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
