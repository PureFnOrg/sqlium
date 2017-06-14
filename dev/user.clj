(ns user
  (:require [clojure.tools.namespace.repl :refer :all]))

(defn dev
  []
  (require 'dev :reload)
  (in-ns 'dev))
