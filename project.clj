(defproject ringmon "0.1.4-SNAPSHOT"
  :description  "Ring middleware to inject web page with nREPL front end"
  :url "https://github.com/zoka/ringMon"
  :dependencies [[org.clojure/clojure "1.4.0"]
                [ring/ring-core "1.1.8"]
                [cheshire "5.1.1"]
                [org.clojure/tools.nrepl "0.2.2"]
                [org.clojure/java.jmx "0.2.0"]]

  :dev-dependencies ; has to be kept for lein 1.x compatibility
                [[ring/ring-jetty-adapter "1.2.0-beta2"]]

  ; lein 2.0 dev-dependencies equivalent
  :profiles {:dev
              {:dependencies
                [[ring/ring-jetty-adapter "1.2.0-beta2"]]}}

  :main         ringmon.server)