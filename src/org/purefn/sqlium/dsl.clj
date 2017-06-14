(ns org.purefn.sqlium.dsl
  "Convenience functions for interacting with sqlium's SQL entity DSL.
   See the two DSL namespaces for more extensive documentation about
   the contents of specs and the return values of the various
   operations "
  (:require [org.purefn.sqlium.dsl.parse :as parse]
            [org.purefn.sqlium.dsl.analyze :as analyze]))

(defn single-relationships
  "Returns the single relationships for an (analyzed) table spec."
  [spec]
  (get-in spec [:relationships :one]))

(defn group-tables
  "Returns a collection all the tables in group for an (analyzed)
   table spec, ie. the table itself and all the single relationships."
  [spec]
  (->> (single-relationships spec)
       (map :target)
       (cons spec)))

(defn relationship?
  "True if spec entry is a relationship."
  [ent]
  (= :relationship (:type ent)))

(defn id-column
  "Returns the id column for a spec as a keyword :table/column."
  [spec]
  (keyword (name (:name spec))
           (name (:id spec))))

(defn parse-spec
  "Takes a DSL expression of a spec, returns parsed spec."
  [dsl-spec]
  (parse/spec dsl-spec))

(defn group-spec
  "Takes a (parsed) spec, returns structure with the relationships
   grouped."
  [spec]
  (analyze/group-relationships spec))

(defn compile-spec
  "Takes a DSL spec, returns map with the parsed spec in :spec key,
   and grouped spec in :grouped key."
  [dsl-spec]
  (let [spec (parse-spec dsl-spec)]
    {:spec spec
     :grouped (group-spec spec)}))
