(ns org.purefn.sqlium.sql
  (:require [clojure.string :as str]
            [clj-time.coerce :as tc]
            [clj-time.format :as tf]
            [org.purefn.sqlium.dsl :as dsl]))

(def field-split-str "_sqlfield_")

(defn- order-rels
  "Recursion for `dependency-ordered`. Consumers should generally only
   call the 2-arg version. available-tables should be a set of table
   names that are already in the relation being joined into."
  ([rels avail-tables]
   (order-rels (into clojure.lang.PersistentQueue/EMPTY rels) avail-tables [] 0))
  ([rels avail-tables ordered iter-num]
   (if-let [rel (first rels)]
     (cond (> iter-num (count rels))
           (throw (ex-info "Unable to order relationships - infinite loop hit."
                           {:remaining rels
                            :ordered ordered
                            :tables-in-relation avail-tables
                            :iterations-with-no-progress iter-num}))
           (contains? avail-tables (get-in rel [:source-table :name]))
           (order-rels (pop rels) (conj avail-tables (get-in rel [:target :name]))
                       (conj ordered rel) 0)
           :else
           (order-rels (-> rels pop (conj rel)) avail-tables ordered (inc iter-num)))
     ordered)))

(defn dependency-ordered
  "Takes a collection of relationships and the root table, and returns
   the relationships in a dependency-satisfied order."
  [root-table rels]
  (order-rels rels #{(:name root-table)}))

(defn column-name
  "Returns the column name from a keyword."
  [kw]
  (let [table (namespace kw)
        column (name kw)]
    (str table (when table ".") column)))

(defn prefixed-column
  "Returns a prefixed column from a table name and column name, for
   use in SQL statements."
  [table column]
  (str table "." column))

(defn alias-column
  "Returns a column alias from a table and column name, for
   deterministic control of query results."
  [table-name column]
  (keyword (str table-name field-split-str column)))

(defn field-column
  "Returns column name from a field as string."
  [field]
  (name (:column field)))

(defn- left-join-statement
  "Builds LEFT JOIN statement, joining target on lfield to rfield."
  [target lfield rfield]
  (format "LEFT JOIN %s ON %s = %s"
          target lfield rfield))

(defn- single-join
  "Returns join in the form returned from table-join for a single
   rel."
  [{:keys [column target] :as rel}]
  [(:name target)
   (column-name column)
   (prefixed-column (:name target) (:id target))])

(defn- many-join
  "Returns join in the form returned from table-join for a many rel."
  [{:keys [column source-table target] :as rel}]
  [(:name target)
   (prefixed-column (:name source-table) (:id source-table))
   (column-name column)])

(defn- table-joins
  "Returns collection of joins for a table, optionally recursively
   calling itself for many relationships. Joins returned are tuple
   vectors in the form:

   [target-table from-field to-field]"
  ([table-spec]
   (table-joins table-spec false))
  ([table-spec recurse?]
   (let [{:keys [one many]} (:relationships table-spec)]
     (when (or (seq one) (seq many))
       (let [single-joins (->> one
                               (dependency-ordered table-spec)
                               (map single-join))
             many-joins (when recurse?
                          (map many-join many))]
         (concat single-joins
                 (when (and (seq many) recurse?)
                   (concat (map many-join many)
                           (mapcat (fn [many-rel]
                                     (table-joins (:target many-rel) recurse?))
                                   many)))))))))

(defn- many-condition
  "Returns the condition clause for a many relationship."
  [rel inputs]
  (let [rel-col (:column rel)]
    (format "%s IN (%s)"
            (column-name rel-col)
            (str/join ", " inputs))))

;;; Main API functions

(defn aliased-fields-statement
  "Takes a map of `:aliased-query-column-name` to `:table/column` and
   returns a SQL fields statement, eg \"SELECT <fields> FROM ...\""
  [alias-map]
  (->> alias-map
       (map (fn [[alias col]]
              (str (column-name col)
                   " AS " (name alias))))
       (str/join ", ")))

(defn from-statement
   "Builds a from statement from table spec. Optionally, recurses
   through the many relationships and adds them to the statement."
  ([table]
   (from-statement table false))
  ([table recurse?]
   (let [table-name (:name table)
         joins (table-joins table recurse?)]
     (str "FROM " table-name " "
          (->> joins
               (map (partial apply left-join-statement))
               (str/join " "))))))

(defn column-mappings
  "Returns a map of `:aliased-query-column-name` to `:table/column`
   from a table spec."
  [table]
  (let [{:keys [name id fields]} table
        columns (-> (map field-column fields)
                    set
                    (conj id))]
    (->> columns
         (map (juxt (partial alias-column name) (partial keyword name)))
         (into {}))))

(defn group-column-mappings
  "Returns a map of `:aliased-query-column` to `:table/column` for all
   the columns in a table spec's query group (itself and its single
   relationships.)"
  [table]
  (->> (dsl/group-tables table)
       (map column-mappings)
       (reduce merge)))

(defn mysql-date-string
  "Takes a date coerceable to LocalDateTime, and returns mysql-formatted
   date time string."
  [date]
  (tf/unparse-local (tf/formatters :mysql)
                    (tc/to-local-date-time date)))

(defn condition-sql
  "Takes a condition map, and optional table alias for the column's
   table, and returns a SQL condition string fragment. Default
   comparator is \"=\".

   Eg, `{:column :foo/bar
         :comparator \">\"
         :value 5}`

   Becomes \"foo.bar > 5\""
  ([condition]
   (condition-sql condition nil))
  ([condition table-alias]
   (let [{:keys [column comparator value]
          :or {comparator "="}} condition]
     (str (or table-alias (namespace column))
          "." (name column)
          " " comparator " "
          (cond
            (string? value) (str "'" value "'")
            (nil? value) (str "NULL")
            :default value)))))

(defn inner-join-sql
  "Takes a join map and returns SQL fragment for an inner join.
   Join map has :base and :target keys, each of which are maps of:

   * :name      table name string
   * :alias     optional table alias string
   * :join-col  string name of the join column"
  [join]
  (let [{:keys [base target]} join]
    (str "INNER JOIN " (:name target) " AS " (:alias target)
         " ON "
         (or (:alias base) (:name base)) "." (:join-col base)
         " = "
         (or (:alias target) (:name target)) "." (:join-col target))))

(defn with-limit
  "Adds a limit and optionally an offset to query string."
  ([query limit]
   (with-limit query limit nil))
  ([query limit offset]
   (str query " LIMIT " limit
        (when offset (str " OFFSET " offset)))))

(defn in-statement
  "Returns an IN statement that can be used in a WHERE clause to match
   a column against a set of values."
  [{:keys [field vals] :as in}]
  (let [formatted-vals (map (fn [x] (if (string? x)
                                      (str \' x \')
                                      x))
                            vals)]
    (str (column-name field) " IN ( "
         (str/join ", " formatted-vals) " )")))

(defn select
  "Builds select statement from table. Optionally, brings in levels of
   many relationships in the from statement to use for filtering.
   Returns tuple of [sql-query column-alias-map]."
  [table]
  (let [cols-map (group-column-mappings table)]
    [(str "SELECT " (aliased-fields-statement cols-map)
          " " (from-statement table)) cols-map]))

(defn many-relationship-select
  "Takes a many relationship and data returned from the parent query
   and builds a query to select the many relationship data. Returns tuple of [sql-query column-alias-map]."
  [rel data]
  (let [source-col (dsl/id-column (:source-table rel))
        rel-column (:column rel)
        table (update (:target rel) :fields
                      conj {:type :field :column rel-column})
        inputs (keep source-col data)
        [select-str cols-map] (select table)]
    (when (seq inputs)
      [(str select-str " WHERE " (many-condition rel inputs)) cols-map])))
