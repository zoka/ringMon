# ringMon


Ring middleware that injects single monitoring page into any Clojure web application
based on Ring or web frameworks such as Noir or Compojure.
The page displays raw JMX data of interest in tree alike structure. It also shows
derived values such as CPU load that is calculated by sampling JMX property OperatingSystem.ProcessCpuTime every
2 seconds, AJAX request statistics and the session count. It is also possible
to force JVM garbage collection.

Moreover, the page provides full featured 
[nREPL](https://github.com/clojure/tools.nrepl) 
front end with syntax colored editor, command history and persistent sessions.

Note that for real life application such a page should be protected by admin access password, since it can
be used to inflict some serious DOS damage to your server. Some sort of authentication
interface is planned for later.

You can see ringMon in action in this Noir application 
at [noirMon at Heroku](http://noirmon.herokuapp.com/).

## Usage (for local test)

```bash
lein deps
lein run
```
If you want to include ringMon in your leiningen project, simply add this to your dependencies:

```clojure
 [ringmon "0.1.0-SNAPSHOT"]
```

In case of bare Ring application such as this one, the following is needed:

```clojure
(ns ringmon.server
  (:require
      [ringmon.monitor          :as monitor]
      [ring.adapter.jetty       :as jetty]))

(defn demo [req]
  (let [headers  (:headers req)
        hostname (get headers "host")
        uri      (:uri req)]
    (if (or (= uri "/") (= uri "/favicon.ico"))
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body "Hello"})))

(def handler
  (-> demo
      ; <-- add your additional middleware here
      (monitor/wrap-ring-monitor)))

(defn -main [& m]
  (let [mode (keyword (or (first m) :dev))
        port (Integer. (get (System/getenv) "PORT" "8081"))]

    (println "The ringMon local demo starting...")
    (jetty/run-jetty handler {:port port})))
```

If you are using Noir, then you need to slightly modify startup in your server.clj,
for example (taken from [noirMon](https://github.com/zoka/noirMon) sample application):

```clojure
(ns noirmon.server
  (:require [noir.server     :as server]
            [noirmon.models  :as models]
            [ringmon.monitor :as monitor]))

(server/load-views "src/noirmon/views/")

(defn -main [& m]
  (let [mode (keyword (or (first m) :dev))
        port (Integer. (get (System/getenv) "PORT" "8080"))]

    (models/initialize) ; initialize blog models

    ; This is the only thing to be done (besides including it
    ; in project depedency) to inject ringMon page into the
    ; application.
    (server/add-middleware monitor/wrap-ring-monitor)

    (server/start port {:mode mode
                        :ns 'noirmon})))
```


## License

Copyright (C) 2012 Zoran Tomicic

Distributed under the Eclipse Public License, the same as Clojure.

