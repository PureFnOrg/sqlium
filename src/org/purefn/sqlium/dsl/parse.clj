(ns org.purefn.sqlium.dsl.parse)

;; TODO: rewrite spec and internal representation examples

(declare spec)

(defn- true-keys
  "Adds keys from collection ks to map m with true vals."
  [m ks]
  (->> ks
       (map (juxt identity (constantly true)))
       (into m)))

(defn- speclist-map
  "Takes a speclist (defined as list of keywords possibly followed by
   one or more values, repeated), returns a map where keywords with no
   value immediately following are keys with true values, and keywords
   followed by non-keywords become normal map entries."
  [specs]
  (let [kvs (->> specs
                 (partition-by keyword?)
                 (partition-all 2))]
    (reduce (fn [m [ks v]]
              (let [[v-k & empty-ks :as all] (reverse ks)
                    empty-ks (if v
                               empty-ks
                               all)]
                (cond-> m
                  v (assoc v-k v)
                  (seq empty-ks) (true-keys empty-ks))))
            {}
            kvs)))

(defn- maybe-generate-id
  "Takes a table spec (with minimum :name and optional :id field) and,
   if id field is not present, generates a default one from the table
   name. Returns updated table spec.

   Generated ids are in the form of \"{table-name}_id\""
  [table-spec]
  (if (:id table-spec)
    table-spec
    (let [table-name (:name table-spec)
          generated-id (str table-name "_id")]
      (assoc table-spec
             :id generated-id
             :id-generated? true))))

(defn table
  [[_ table-sym & table-spec]]
  (assert (keyword? (first table-spec)))
  (let [{:keys [id fields]} (speclist-map table-spec)
        base-table (maybe-generate-id {:type :table
                                       :name (name table-sym)
                                       :id (first id)})]
    (cond-> base-table
      (seq fields)
      (assoc :fields (map (partial spec base-table) fields)))))

(defn string-field
  [s for-table]
  {:type :field
   :column (keyword s)})

(defn field-spec
  [[field & opts] for-table]
  (let [xform? (some-fn symbol? list? (partial instance? clojure.lang.Cons))
        {:keys [as] :as opts-m} (speclist-map (remove xform? opts))
        xform (first (filter xform? opts))]
    (-> opts-m
        (dissoc :as)
        (merge {:type :field
                :column (keyword field)})
        (cond->
            as (assoc :alias (first as))
            xform (assoc :transform xform)))))

(defn relationship
  [rel-m for-table]
  (let [;; since relationships are maps where one keyval is the
        ;; relationhsip def, and the other keys are other keyword
        ;; params, we need to separate out the relationship def from
        ;; the other params.
        {[rel-field & invalid?] false other-params true} (group-by keyword? (keys rel-m))
        _ (assert (empty? invalid?) (str "Invalid relationship spec " rel-m))
        table-spec (get rel-m rel-field)
        _ (assert (list? table-spec) (str "Invalid table spec " table-spec))
        target-table (table table-spec)
        {:keys [column alias flatten] :as field-spec} (spec target-table rel-field)
        reverse-ref? (.startsWith (name column) "_")
        rel-column (if reverse-ref?
                     (keyword (:name target-table) (subs (name column) 1))
                     (keyword (:name for-table) (name column)))]
    (merge (select-keys rel-m other-params)
           (select-keys field-spec [:alias :flatten])
           {:type :relationship
            :source-table for-table
            :column rel-column
            :target target-table})))

(defn spec
  "Takes dsl element x and optionally the spec for the table it belongs to,
   and returns the parsed datastructure."
  ([x]
   (spec {} x))
  ([for-table x]
   (let [parsed
         (cond
           (string? x) (string-field x for-table)
           (vector? x) (field-spec x for-table)
           (map? x) (relationship x for-table)
           (list? x) (table x))]
     (assert parsed (str "Invalid spec: " x))
     parsed)))
