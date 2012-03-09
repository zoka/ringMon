(defproject noirmon "0.1.0-SNAPSHOT"
            :description "Ring handler to inject web page with nREPL front end"
            :dependencies [[org.clojure/clojure "1.3.0"]
                           [ring "1.0.1"]
                           [org.clojure/java.jmx "0.1"]
                           [clj-json "0.5.0"]
                           [org.clojure/tools.nrepl "0.2.0-beta2"]]
            :main ringmon.server)

