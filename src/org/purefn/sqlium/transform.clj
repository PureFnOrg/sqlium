(ns org.purefn.sqlium.transform
  "Functions that deal with transforming data returned from the
   service's SQL queries into the structure defined by the data spec."
  (:require [clojure.string :as str]
            [clojure.set :as set]))

(defn binary-string
  "Takes a byte array, returns a UTF-encoded string."
  [byte-array]
  (when byte-array
    (String. byte-array "UTF-8")))

(defn xform-fn
  "Takes the xform field from a field spec and returns a transform
   function. Handles mapping of built-in transforms."
  [xform]
  (binding [*ns* (the-ns 'org.purefn.sqlium.transform)]
    (eval xform)))

(defn- alias-path
  "Transforms an alias into either a keyword, or path vector of
   keywords if it contains a path."
  [alias]
  (let [path (map keyword (str/split alias #"\."))]
    (if (> (count path) 1)
      (vec path)
      (first path))))

(defn field-transform
  "Takes a table spec and field spec, returns a function that takes a record and
   returns a keyval for the transformed field."
  [table field]
  (let [table-name (:name table)
        {:keys [alias column transform]} field
        field-key (if alias
                    (alias-path alias)
                    column)
        source (keyword (name table-name)
                        (name column))
        xform (if transform
                (xform-fn transform)
                identity)]
    (fn [record]
      [field-key (xform (get record source))])))

(defn table-fields-transform
  "Takes a table spec, returns a function that takes a record and
   returns a map with the table data formatted according to the table
   spec."
  [table-spec]
  (let [record-fields (if (seq (:fields table-spec))
                        (->> (:fields table-spec)
                             (map (partial field-transform table-spec))
                             (apply juxt))
                        (constantly nil))]
    (fn [record]
      (reduce (fn [m [k-path v]]
                (cond (nil? v)
                      m
                      (vector? k-path)
                      (assoc-in m k-path v)
                      :else
                      (assoc m k-path v)))
              {}
              (record-fields record)))))

(defn single-relationship-transform
  "Takes a spec for a single relationship, and returns a function of
   two args: the record to be transformed for the relationship, and
   the already-transformed data map, that returns the updated data map
   with the relationship data added according to the relationship
   spec."
  [one-rel]
  (let [{:keys [target path]} one-rel
        table-xform (table-fields-transform target)
        merge-fn (if (seq path)
                   (fn [data table-data]
                     (update-in data (vec path) merge table-data))
                   (fn [data table-data]
                     (merge table-data data)))]
    (fn [record data]
      (let [table-data (table-xform record)]
        (merge-fn data table-data)))))

(declare group-transform)

;; TODO: in analysis phase, make sure to re-root the path for a many
;; relationship's group

(defn many-relationship-transform
  "Takes a spec for a many relationship, and returns a function that
   takes a record and transformed data, and returns the transformed
   data with the many relationship data added according to the
   relationship spec."
  ;; Here be recursion
  [many-rel]
  (let [{:keys [alias column target path]} many-rel
        xform (group-transform target)
        source-field [:many-relationships column]]
    (fn [record data]
      (let [xformed (map xform (get-in record source-field))]
        (assoc-in data (vec path) xformed)))))

(defn group-transform
  "Main API function. Takes a root table spec, returns a function that
   takes a record and transforms the data for the group - the root
   table and its relationships, recursively transforming many
   relationship data - according to the spec."
  [table-spec]
  (let [single-rels (get-in table-spec [:relationships :one])
        many-rels (get-in table-spec [:relationships :many])
        base-xform (table-fields-transform table-spec)
        single-transforms (map single-relationship-transform single-rels)
        many-transforms (map many-relationship-transform many-rels)]
    (fn [record]
      (reduce (fn [xformed-data xf]
                (xf record xformed-data))
              (base-xform record)
              (concat single-transforms many-transforms)))))
