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
    (some-> (import/import-record db compiled-spec id)
            xform
            (vary-meta assoc ::id id))))

(defn record-ids
  "Returns an ArrayList of record ids for spec, according to supplied
   selection options (which can be supplied either as a map or
   kwargs). If no options are supplied, all record ids will be
   returned. There are several types of selections possible. If
   multiple selections are supplied, only the highest-precedence will
   be used. From highest to lowest precedence:

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
  [db spec & selection]
  (let [opts (if (= (count options))
               (first options)
               (apply hash-map options))
        compiled (dsl/compile-spec spec)]
    (import/fetch-ids db compiled opts)))

(defn records
  "Returns a lazy sequence of records for spec, querying from jdbc
   datasource db. Takes optional parameters as kwargs or a map that
   control which entities to retrieve and behavior about how they are
   retrieved.

   There are several ways to specify which entities to fetch: a
   specific list of ids, from a database table that lists ids with
   update times, by using update datetimes in the entities themselves,
   or entities that are newer than a given expiry. Only the
   highest-precedence method supplied will be used. In order of
   precedence:

    :ids        A collection of ids to retrieve.

    :update - uses a database table that lists entity ids with update
              dates, specified as a map with:
     * :table    string name of the update table.
     * :id       string name of the column containing entity ids.
     * :updated  optional string name of the field containing the
                 entity update datetime.
     * :date     optional, anything that can be coerced to a DateTime.
                 Only entities in the table with an updated datetime
                 newer than this will be returned.

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
