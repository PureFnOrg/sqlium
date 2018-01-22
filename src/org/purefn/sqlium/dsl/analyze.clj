(ns org.purefn.sqlium.dsl.analyze)

(defn one-to-one?
  "True if relationship for table is a one-to-many."
  [{:keys [source-table column] :as relationship}]
  (= (:name source-table)
     (namespace column)))

(def one-to-many?
  (complement one-to-one?))

(defn relationship?
  "True if x is a relationship spec."
  [x]
  (= :relationship (:type x)))

(defn field?
  "True if x is a field spec."
  [x]
  (= :field (:type x)))

(defn relationship-type
  "Takes a table and relationship. Returns sither :one-to-one if a
   one-to-one relationship, or :one-to-many if a one-to-many
   relationship."
  [relationship]
  (assert (relationship? relationship))
  (cond (one-to-many? relationship) :many
        (one-to-one? relationship) :one))

(defn group-fields
  "Takes collection of fields, groups by field :type."
  [fields]
  (group-by :type fields))

(defn by-type
  "Groups relationships by relationship type."
  [relationships]
  (group-by relationship-type relationships))

(defn add-path
  "Adds relationship's top-level path, using either the :as alias or
   column name as the segment."
  [relationship]
  (let [{:keys [flatten alias column]} relationship
        many? (one-to-many? relationship)
        path-seg (if flatten
                   []
                   [(keyword (cond alias (name alias)
                                   many? (str "_" (name column))
                                   :else (name column)))])]
    (assoc relationship :path path-seg)))

(defn path-prepender
  "Takes a path segment and returns a function that takes a
   relationship and returns the relationship with the path segment
   prepended to its path."
  [path-seg]
  (let [prepend-path (partial concat path-seg)]
    (fn [rel]
      (update rel :path prepend-path))))

(defn- splice-rels-with-path
  "Takes a relationship type (either `:one` or `:many`) and a
   collection of relationships, and returns a tuple: a collection of
   the first level of nested relationships of the given type with the
   path segment from their parent relationship prepended to their
   path, and the input relationships with the nested relationships of
   that type removed."
  [type rels]
  (assert (#{:one :many} type))
  (let [spliced (mapcat (fn [rel]
                          (let [path-prepend (path-prepender (:path rel))]
                            (->> (get-in rel [:target :relationships type])
                                 (map path-prepend))))
                        rels)
        rels-without-spliced (map (fn [rel] (update-in rel [:target :relationships] dissoc type)) rels)]
    [spliced rels-without-spliced]))

(defn promote-relationships
   "Takes a relationship map with `:one` and `:many` keys. Returns a
   new relationship map where the first level of nested `:one`
   relationships has been promoted to the top level, and the `:many`
   relationships of all the resulting single relationships have been
   promoted top the top level `:many` relationships. Promoted
   relationships have the path segment from their parent's path
   prepended to their own."
  [rels]
  (let [{:keys [one many]} rels
        one-with-promoted (apply concat (splice-rels-with-path :one one))
        [promoted-many one-without-many] (splice-rels-with-path :many one-with-promoted)]
    (cond-> {:one one-without-many}
      (or (seq many) (seq promoted-many))
      (assoc :many (concat many promoted-many)))))

(defn repeated-join-tables
  "Takes a collection of relationships and returns a collection of any
   repeated tables, or nil if none."
  [rels]
  (some->> rels
           (group-by (comp :name :target))
           (filter (fn [[table rels]]
                     (and table (> (count rels) 1))))
           (seq)
           (into {})))

;; TODO: build some tests around complicated path scenarios, figure
;; out re-rooting for many paths.

(defn group-relationships
  "Takes a parsed data spec. Breaks out relationships from a table's
   fields, groups them into one-to-one and one-to-many, promotes the
   one-to-ones up to the root level, and also promotes the first level
   of many-to-many to the root level.

   It also builds a path for each relationship that describes how to
   associate data into the correct shape for the spec.

   The effect is that all the groups of one-to-one relationships that
   can be queried from the database together are grouped at the same
   level together, and all of each group's one-to-many relationships
   are at the top level of the group.

   One-to-many relationships constitute deeper levels in the tree.

   Eg., a spec like this (non-essential parts elided):
   {:type :table
    :name \"foo\"
    :fields ({:type :relationship
              :source-table {:name \"foo\"}
              :column :foo/bar_id
              :target
              {:type :table
               :name \"bar\"
               :fields ({:type :relationship
                         :source-table {:name \"bar\"}
                         :column :bar/fizz_id
                         :target
                         {:type :table
                          :name \"fizz\"
                          :fields ({:type :relationship
                                    :source-table {:name \"fizz\"}
                                    :column :buzz/fizz_id
                                    :target
                                    {:type :table
                                     :name \"buzz\"}})}})}}
             {:type :relationship
              :source-table {:name \"foo\"}
              :column :baz/foo_id
              :target
              {:type :table
               :name \"baz\"}})}

   Would get analyzed to:
   {:type :table
    :name \"foo\"
    :relationships
    {:one [{:type :relationship
            :source-table {:name \"foo\"}
            :column :foo/bar_id
            :path (:bar_id)
            :target {:type :table
                     :name \"bar\"}}
           {:type :relationship
            :source-table {:name \"bar\"}
            :column :bar/fizz_id
            :path (:bar_id :fizz_id)
            :target {:type :table
                     :name \"fizz\"}}]
     :many [{:type :relationship
             :source-table {:name \"foo\"}
             :column :baz/foo_id
             :path
             :target {:type :table
                      :name \"baz\"}}
            {:type :relationship
             :source-table {:name \"fizz\"}
             :column :buzz/fizz_id
             :target {:type :table
             :name \"buzz\"}}]}} "
  [table]
  (let [fields (filter field? (:fields table))
        relationships
        (->> (:fields table)
             (filter relationship?)
             (map (fn [r] (update r :target group-relationships)))
             (map add-path)
             (by-type))
        promoted-relationships (promote-relationships relationships)
        repeated (repeated-join-tables (:one promoted-relationships))]
    (when repeated
      (throw (ex-info "Invalid relationships detected - repeated use of the same table."
                      {:repeated repeated
                       :source-table (dissoc table :fields)})))
    (assoc table
           :fields fields
           :relationships promoted-relationships)))
