(defproject ringmon "0.1.3-SNAPSHOT"
  :description  "Ring middleware to inject web page with nREPL front end"
  :url "https://github.com/zoka/ringMon"
  :dependencies [[org.clojure/clojure "1.3.0"]
                [ring/ring-core "1.0.1"]
                [org.codehaus.jackson/jackson-core-asl "1.5.0"]
                [org.clojure/tools.nrepl "0.2.0-beta2"]
                [org.clojure/java.jmx "0.1"]]

  :dev-dependencies ; has to be kept for lein 1.x compatibility
                [[ring/ring-jetty-adapter "1.0.1"]]

  ; lein 2.0 dev-dependencies equivalent
  :profiles {:dev
              {:dependencies
                [[ring/ring-jetty-adapter "1.0.1"]]}}

  :main         ringmon.server)
