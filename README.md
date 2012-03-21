# ringMon

Ring middleware that injects single monitoring page into any Clojure web application
based on Ring or on higher level web frameworks such as Noir or Compojure. It is
also easily added as a dev dependency to non-web Clojure apps as well.
Actually, it can be incorporated into any JVM (non-Clojure) application with
bit more work - planned for later.

The page displays raw JMX data of interest in tree alike structure. It also shows
derived values such as CPU load that is calculated by sampling JMX property OperatingSystem.ProcessCpuTime every
2 second and AJAX request statistics. It is also possible
to force JVM garbage collection.

Moreover, the page provides full featured
[nREPL](https://github.com/clojure/tools.nrepl)
front end with syntax colored editor, command history and persistent sessions.

Note that for real life application there sould be some
mechanism to prevent unathorised access. There is a pluggable authenication
function that will be called upon every AJAX command or ringMon page
request. The function is passed the pending Ring request map, and then it can
decide wether to pass the requets to be processed, or to reject it.
Simple authentication mechanism woukd be to have white list of IP addresses
allowed.

ringMon can be very useful to provide nREPL access to cloud services
such as Heroku. Heroku has restriction of one server socket per web app,
so the convenient way to share nREPL communication with normal web
server traffic was to implement ringMon as a Ring middleware.

The communication path for request from browser to nREPL server is:

```
browser(js)->AJAX->ringMon(clj)->Custom-in-JVM-trasport(clj)->nREPLserver(clj)
```

The reply travels all the way back in reverse order.

You can see ringMon in action in this Noir application
at [noirMon at Heroku](http://noirmon.herokuapp.com/).

## Chat Facility

ringMon monitoring page supports simple chat facility. This may be assist
remote team members when they work together on the same deplyed application
insrance. Or it can be just a fun tool to provide bit of social REPL-ing,
such as [noirMon](http://noirmon.herokuapp.com/) does.

## Usage (for local test)

```bash
lein deps
lein run
```
If you want to include ringMon in your leiningen project,
simply add this to your dependencies:

```clojure
 [ringmon "0.1.1"]
```

To track the latest snapshot use:

```clojure
 [ringmon "0.1.2-SNAPSHOT"]
```


In case of bare bones Ring application, the following is needed:

```clojure
(ns my.server
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

(defn -main []
  (let [port (Integer. (get (System/getenv) "PORT" "8080"))]

    (println "The ringMon bare bones local demo starting...")
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

