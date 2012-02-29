(defproject noirmon "0.1.0-SNAPSHOT"
            :description "Noir App monitoring demo"
            :dependencies [[org.clojure/clojure "1.3.0"]
                           [noir "1.2.2"]
                           [org.clojure/java.jmx "0.1"]
                           [org.clojure/tools.nrepl "0.2.0-beta2"]]
            :main noirmon.server)

