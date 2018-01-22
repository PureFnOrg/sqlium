(defproject org.purefn/sqlium "0.1.3-SNAPSHOT"
  :description "A flexible, Datomic-inspired, config-driven Extraction system (big E in ETL)."
  :url "http://github.com/TheLadders/sqlium"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/java.jdbc "0.6.1"]
                 [org.clojure/tools.logging "0.4.0"]
                 [clj-time "0.13.0"]]
  :profiles {:dev {:dependencies [[org.clojure/tools.namespace "0.2.11"]
                                  [mysql/mysql-connector-java "5.1.6"]
                                  [org.postgresql/postgresql "42.1.1"]]
                   :source-paths ["dev"]}})
