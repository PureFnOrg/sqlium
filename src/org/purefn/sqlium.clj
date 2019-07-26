(ns org.purefn.sqlium
  "A flexible, config-driven, Datomic-inspired entity extraction
   system. sqlium provides a declarative DSL to describe entities
   stored in a relational database - particularly those that are
   composed of many tables with nested relationships - and mechanisms
   to read and transform them into Clojure datastructures."
  (:require [org.purefn.sqlium.dsl :as dsl]
            [org.purefn.sqlium.import :as import]
            [org.purefn.sqlium.transform :as transform]))

(defn id
  "Returns the id of an entity."
  [entity]
  (::id (meta entity)))

(defn entity
  "Returns a single entity by id for given spec, querying from jdbc
   datasource db."
  [db spec id]
  (let [compiled-spec (dsl/compile-spec spec)
        xform (transform/group-transform (:grouped compiled-spec))]
    (some-> (import/import-record db compiled-spec id)
            xform
            (vary-meta assoc ::id id))))

(defn entity-ids
  "Returns an ArrayList of record ids for spec from jdbc datasource db
   according to supplied selection options (which can be supplied
   either as a map or kwargs). If no options are supplied, all record
   ids will be returned.

   There are several ways to specify which entities to fetch: from a
   database table that lists ids with update times, by using update
   datetimes in the entities themselves, or entities that are newer
   than a given expiry. Only the highest-precedence method supplied
   will be used. In order of precedence:

    :update-table - uses a database table that lists entity ids with
                    update dates, specified as a map with:
     * :table    string name of the update table.
     * :id       string name of the column containing entity ids.
     * :updated  optional string name of the field containing the
                 entity update datetime.
     * :date     optional, anything that can be coerced to a DateTime.
                 Only entities in the table with an updated datetime
                 newer than this will be returned.

    :delta - uses datetime fields in the entity itself to detect
             entities that have updates since a given time.
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
  (let [opts (if (= 1 (count selection))
               (first selection)
               (apply hash-map selection))
        compiled (dsl/compile-spec spec)]
    (import/fetch-ids db compiled opts)))

(defn entities
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

    :ids         A collection of ids to retrieve. This is generally
                 the preferred way, and if you need the functionality
                 of the other  selection methods then you can use
                 `entity-ids` to fetch the ids to pass here.

    :update-table - uses a database table that lists entity ids with
                    update dates, specified as a map with:
     * :table    string name of the update table.
     * :id       string name of the column containing entity ids.
     * :updated  optional string name of the field containing the
                 entity update datetime.
     * :date     optional, anything that can be coerced to a DateTime.
                 Only entities in the table with an updated datetime
                 newer than this will be returned.

    :delta - uses datetime fields in the entity itself to detect
             entities that have updates since a given time.
     * :fields   collection of :table/column datetime fields which will
                 be compared with :date to detect updated data
     * :date     anything that can be coerced to a DateTime; the records
                 returned will be newer than this date

    :expiry - a map with:
     * :field    :table/column keyword for the datetime field that
                 determines the age of the entity
     * :age      maximum age before the entity is ignored, either as an
                 integer number of days or an expiration date as something
                 that can be coerced to a DateTime

   Sqlium fetches entities in batches for efficiency. By default it
   uses a rather large batch size (10,000) suitable for smaller
   entities. You can override this by passing a number in the :batch
   option or you can disable batching by setting :batch to false."
  [db spec & options]
  (let [opts (if (= 1 (count options))
               (first options)
               (apply hash-map options))
        ids (or (:ids opts)
                (entity-ids opts))
        compiled (dsl/compile-spec spec)
        xform (transform/group-transform (:grouped compiled))
        id-col (dsl/id-column (:spec compiled))
        records (import/fetch-records db compiled ids opts)]
    (with-meta (sequence (map (fn [r] (with-meta (xform r)
                                        {::id (get r id-col)})))
                         records)
      (meta records))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Deprecated API functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Deprecated in 0.2.0, will be removed in the future

(def ^:deprecated
  entity-id
  "See: `id`"
  id)

(def ^:deprecated
  records
  "See: `entities`"
  entities)
