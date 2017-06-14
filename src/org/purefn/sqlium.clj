(ns org.purefn.sqlium
  "A flexible, config-driven, Datomic-inspired entity extraction
   system. sqlium provides a declarative DSL to describe entities
   stored in a relational database - particularly those that are
   composed of many tables with nested relationships - and mechanisms
   to read and transform them into Clojure datastructures."
  (:require [org.purefn.sqlium.dsl :as dsl]
            [org.purefn.sqlium.import :as import]
            [org.purefn.sqlium.transform :as transform]))

(defn record-id
  "Returns the id of a record."
  [record]
  (::id (meta record)))

(defn entity
  "Returns a single entity by id for given spec, querying from jdbc
   datasource db."
  [db spec id]
  (let [compiled-spec (dsl/compile-spec spec)
        xform (transform/group-transform (:grouped compiled-spec))]
    (vary-meta (some-> (import/import-record db compiled-spec id)
                       xform)
               assoc ::id id)))

(defn records
  "Returns a lazy sequence of records for spec, querying from jdbc
   datasource db. Takes optional parameters as kwargs or a map to
   either control expiry, return updated data since a given date by
   comparing against provided date time fields, or return updated data
   based on a specific update table. Only the highest-precedence
   option present will be used. In order of precedence:

   :update - a map with:
     * :table    string name of the update table
     * :id       string name of the column containing entity ids to update
     * :updated  string name of the field containing the entity update time
     * :date     anything that can be coerced to a DateTime; the records
                 returned will be newer than this date

   :delta - a map with:
     * :fields   collection of :table/column datetime fields which will
                 be compared with :date to detect updated data
     * :date     anything that can be coerced to a DateTime; the records
                 returned will be newer than this date

   :expiry - a map with:
     * :field    :table/column keyword for the datetime field that
                 determines the age of the entity
     * :age      maximum age before the entity is ignored, either as an
                 integer number of days or an expiration date as something
                 that can be coerced to a DateTime"
  [db spec & options]
  (let [opts (if (= 1 (count options))
               (first options)
               (apply hash-map options))
        compiled (dsl/compile-spec spec)
        xform (transform/group-transform (:grouped compiled))
        id (dsl/id-column (:spec compiled))
        records (import/import-table db compiled opts)]
    (with-meta (sequence (map (fn [r] (with-meta (xform r)
                                        {::id (id r)})))
                         records)
      (meta records))))
