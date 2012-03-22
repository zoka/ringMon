# ringMon

Ring middleware that injects single monitoring page into a Clojure
web application based on
[Ring]( https://github.com/mmcgrana/ring)
library or on higher level web frameworks such as
[Noir](https://github.com/ibdknox/noir)
or
[Compojure](https://github.com/weavejester/compojure).
It is also easily added as a dev dependency to non-web Clojure applications
as well. Actually, it could be incorporated into any JVM (non-Clojure) application
with bit more work - planned for later.

The page displays raw JMX data of interest in tree alike structure.
It also shows
derived values such as CPU load that is calculated by
sampling JMX property OperatingSystem.ProcessCpuTime every
2 seconds and statistics for AJAX requests to the middlware itsef.
There is also a button to force JVM garbage collection.

Moreover, the page provides full featured
[nREPL](https://github.com/clojure/tools.nrepl)
front end with syntax colored editor, command history and persistent sessions.

Note that for real life application there should be some
mechanism to prevent unathorised access. There is a pluggable authenication
function that will be called upon every AJAX command or ringMon page
request. The function is passed the pending Ring request map, in order
to decide  whether to accept it or reject it.
Simple authentication mechanism woukd be to have white list of IP addresses
allowed.

ringMon can be very useful to provide nREPL access
to applications deployed to cloud platforms
such as Heroku. Heroku has restriction of one server socket per web app,
so the convenient way to share nREPL communication with normal web
server traffic was to implement ringMon as a Ring middleware.

The communication path for request from browser to nREPL server is:

```
browser(js)->AJAX->ringMon(clj)->Custom-intra-JVM-trasport(clj)->nREPLserver(clj)
```

The reply travels all the way back in reverse order. The nREPL provides
 pluggable transport architecture. This particular one is based on
 pair of
 [LinkedBlockingQueue](http://docs.oracle.com/javase/6/docs/api/java/util/concurrent/LinkedBlockingQueue.html)s
 in back to back configuration - very fast and no need for
 using bencode since the commands and responses are sent as
 native Clojure data structures.

## Demo

You can see ringMon in action in
[this](https://github.com/zoka/noirMon)
showcase Noir application deployed
at [Heroku](http://noirmon.herokuapp.com/).

## Chat facility

ringMon monitoring page supports a simple chat facility. This may assist
remote team members when they work together on the same deployed application
instance. Or it can be just a fun tool to provide bit of social REPL-ing,
such as [noirMon](http://noirmon.herokuapp.com/ringmon/monview.html) does.

## Usage (for local test)

```bash
lein deps
lein run
```
Point your browser to `localhost:8081`.

## Using ringMon in your applications

If you want to include ringMon in your leiningen project,
simply add this to your dependencies:

```clojure
 [ringmon "0.1.1"]
```

To track the latest snapshot (recommended) use:

```clojure
 [ringmon "0.1.2-SNAPSHOT"]
```
### Bare bones

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

Point your browser to `localhost:8080` amd you will see `Hello` response.
The monitoring page is at `localhost:8080/ringmon/monview.html`.

### Noir

If you are using Noir, then you need to slightly
modify startup in your server.clj:

```clojure
(ns myapp.server
  (:require [noir.server     :as server]
            [ringmon.monitor :as monitor])) ; 1. ringmon.monitor is required

(server/load-views "src/myapp/views/")

(defn -main [& m]
  (let [mode (keyword (or (first m) :dev))
        port (Integer. (get (System/getenv) "PORT" "8080"))]

    ; 2. add ringmon middlevare _before_ starting the server
    (server/add-middleware monitor/wrap-ring-monitor)

    (server/start port {:mode mode
                        :ns 'noirmon})))
```
Again, the monitoring page is at `localhost:8080/ringmon/monview.html`.
You might want to add more covenient link to this page in your appllication
like it was done in [noirMon](http://noirmon.herokuapp.com/).

### As a replacemnet for `lein repl`

Even if your Clojure applicarion is not web based, you can add
ringMon to dev dependecies and use it as a replacement for
built in REPL.

In this case ringMon runs first:

```clojure
lein run -m ringmon.server "{:local-repl true :local-port 0}"
```
This will start a separate Jetty instance on autoselected server
port just to serve the nREPL page. Your default browser will automatically
start and load the monitoring page. If `:local-port` has
non-zero value then there will be no port autoselection. Default
value is `8081`

At this point your application is not runnning yet.
You can start it entering this in nREPL input window:

```clojure
(use 'your.app.main.namespace)
(-main)
```
### Run-time configuration

The map bellow shows the default runtime configuration. It is typically
adjusted before web server starts, but it may be changed later as well.

```clojure
; the middleware configuration
(def the-cfg (atom
  {:local-repl nil      ; running on local machine on separate Jetty instance
   :local-port nil      ; on this port (if set to zero, will be auto selected)
                        ; those local- prefixed values are set in ringmon.server
   :fast-poll  500      ; browser poll when there is REPL output activity
   :norm-poll  2000     ; normal browser poll time
   :parent-url ""       ; complete url of the parent application main page
   :disabled   nil      ; general disable, if true then check :the auth-fn
   :auth-fn    nil}))   ; authorisation callback, checked only if :disabled is true
                        ; will be passed a Ring request, return true if Ok
```
Use this function `ringmon.monitor/merge-cfg` to change the configuration
safely.

```clojure
(defn merge-cfg
  [cfg]
  (when (map? cfg)
    (swap! the-cfg merge cfg)))
```

## License

Copyright (C) 2012 Zoran Tomicic

Distributed under the Eclipse Public License, the same as Clojure.

