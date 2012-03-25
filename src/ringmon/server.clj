(ns ringmon.server
  (:require
      [ringmon.monitor          :as monitor]
      [ring.util.response       :as resp])

  (:import (java.net Socket ServerSocket URI)
           (java.awt Desktop)))

(defn default [req]
  (resp/redirect "/ringmon/monview.html"))

(def handler
  (-> default
      (monitor/wrap-ring-monitor)))

; default configuration for standalone mode
; or for using ringmon as equivalent for 'lein repl'
; "lein repl" for any Clojure application
(def loc-cfg (atom {:fast-poll    200  ; assuming running within local
                    :norm-poll    500  ; machine or on intranet
                    :port         8888 ; default port, will be autoselected if
                                       ; set to zero
                    :local-repl   nil  ; will autostart default browser if true
                    :ring-handler handler ; redirect to ringMon page
                    :http-server  nil}))  ; will be Jetty if not set

(defn start-browser
  "Start the default desktop browser with target uri"
  [target]
  (let [sup (Desktop/isDesktopSupported)]
    (when sup
      (let [dtop (Desktop/getDesktop)
            br   java.awt.Desktop$Action/BROWSE ; access enumeration
            sup (.isSupported dtop br)]
        (when sup
          (try
            (let [uri (java.net.URI. target)]
              (.browse dtop uri))
            (catch Exception e
              (println "Could not browse to" target "\n" e))))))))

(defn port-avaliable?
 "Check if TCP port is available."
  [port]
  (try
    (let [ss (ServerSocket. port)]
      (.close ss)
      true)
      (catch Exception e
        (println "Port" port "is not available."))))

(defn get-port
 "Get suitable port for http server, either autoselected or
  precofigured."
  []
  (when (:local-repl @loc-cfg)
    (let [port (:port @loc-cfg)]
      (when (= port 0)
        (let [ss (ServerSocket. 0)
              port (.getLocalPort ss)]
          (swap! loc-cfg assoc :port port)
          (.close ss)))))
  (let [port (:port @loc-cfg)] ; final check
    (if (port-avaliable? port)
      port
      nil)))

(defn get-http-server-start-fn
  []
  (require 'ring.adapter.jetty)
  (if-not (:http-server @loc-cfg)
    (resolve 'ring.adapter.jetty/run-jetty) ; force runtime resolution of Jetty
    (:http-server @loc-cfg)))

(defn start
 "Starts ringMon in standalone mode, using its own http server
  instance on appropriate port, depending on @loc-cfg.
  Expects either no parameters (all defaults from @loc-cfg above)
  or configuration map with amended values.
  Returns true if succesful."
  [& cfg]
  (println "Starting with configuration:" cfg)
  (let [cfg (first cfg)] ; only one optional map expected
    (when (or (nil? cfg) (map? cfg))
      (swap! loc-cfg merge cfg)
      (let [port        (get-port)
            handler     (:ring-handler @loc-cfg)
            http-server (get-http-server-start-fn)]
        (when (and http-server port handler)
          (monitor/merge-cfg @loc-cfg)
          (future
            (http-server handler {:port port}))
          (Thread/sleep 100) ; allow some time for http-server to start
          (when (:local-repl @loc-cfg)
            (start-browser (str"http://localhost:"
                           (str (:port @loc-cfg))
                           "/ringmon/monview.html")))
          true)))))

(defn string->map
  [s]
 "Converts configuration map in string form into Clojure map"
  (if s
    (try
      (let [cfg (read-string s)]
        (if-not (map? cfg)
          {}
          cfg))
      (catch Exception e
        (println "Invalid configuration map\nException:" e)
        (Thread/sleep 100)))  ; let the exception print out in peace
    {}))

(defn -main
 "Command line invocation for standalone mode - to be
  invoked with 'lein run'. Relies on
  'ring/ring-jetty-adapter' being in development dependencies.
  Expects either no parameters, or one
  configuration map as a string in quotes. For example:
  lein run -m ringmon.server \"{:port 10000 :local-repl true}\"
  This will start the dedicated http-server on port 10000 and
  autostart the browser at the REPL interface page."
  [& cfg-pars]
  (let [cfg-str (first cfg-pars)
        cfg     (string->map cfg-str)
        ok      (start cfg)]
        (print "The standalone ringMon ")
        (if ok
          (println (str "up and running using port "
                     (:port @loc-cfg)"."))
          (println "failed to start."))))



