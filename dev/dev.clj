(ns dev
  (:require [clojure.java.io :as io]
            [clojure.java.jdbc :as jdbc]
            [clojure.pprint :refer (pprint)]
            [clojure.repl :refer :all]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.namespace.repl :refer :all]

            [org.purefn.sqlium :as sqlium]))
