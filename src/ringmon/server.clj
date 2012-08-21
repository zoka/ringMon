(ns ringmon.server
  (:require
      [ringmon.monitor          :as monitor]
      [ring.util.response       :as resp])

  (:import (java.net Socket ServerSocket URI)
           (java.awt Desktop)))

(defn- default [req]
  (resp/redirect "/ringmon/monview.html"))

(def handler
  (-> default
      (monitor/wrap-ring-monitor)))

; default configuration for standalone mode
; or for using ringmon as equivalent for 'lein repl'
; "lein repl" for any Clojure application
(def loc-cfg (atom
 {:fast-poll    200     ; assuming running within local
  :norm-poll    500     ; machine or on intranet
  :port         8888    ; default port, will be autoselected if set to zero
  :local-repl   nil     ; will open the default browser window if true
  :ring-handler handler ; simple handler redirect to ringMon page
  :http-server  nil}))  ; will be Jetty if not set

(defn- open-browser-window
  "Open the default desktop browser window with target uri"
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

(defn- port-avaliable?
 "Check if TCP port is available."
  [port]
  (try
    (let [ss (ServerSocket. port)]
      (.close ss)
      true)
      (catch Exception e
        (println "Port" port "is not available."))))

(defn- get-port
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

(defn- get-http-server-start-fn
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
  [cfg]
  (when (or (nil? cfg) (map? cfg))
    (swap! loc-cfg merge cfg)
    (let [port        (get-port)
          handler     (:ring-handler @loc-cfg)
          http-server (get-http-server-start-fn)]
      (when (and http-server port handler)
        (monitor/merge-cfg @loc-cfg)
        (println "[ringMon server] starting with:" @loc-cfg)
        (future
          (http-server handler {:port port}))
        (when (:local-repl @loc-cfg)
          (Thread/sleep 100) ; allow some time for http-server to start
          (open-browser-window
            (str"http://localhost:"
            (str (:port @loc-cfg))
            "/ringmon/monview.html")))
        true))))

(defn- cfg->map
  [cfg]
 "Convert list of cfg pairs in keyword/value strings  
  form into a Clojure map."
  (if-not cfg
    {}
    (let [p (reduce #(str %1 " " %2) cfg)
          s (str "{" p "}")]
      (try
        (let [cfg (read-string s)]
         (if-not (map? cfg)
           {}
           cfg))
        (catch Exception e
          (println "Exception while parsing command line:\n" e)
          (Thread/sleep 100) ; let the exception print out in peace
          nil)))))

(defn -main
 "Command line invocation for standalone mode - to be
  invoked with 'lein run'. Relies on 'ring/ring-jetty-adapter'
  being at least in development dependencies. 
  Expects either no parameters, or a sequence of keyword/value 
  pairs. For example:
  lein run -m ringmon.server :port 9999 :local-repl true
  This will start a dedicated Jetty http-server on port 9999
  and create a fresh web browser window with ringMon's 
  nREPL interface page loaded."
  [& cfg-pars]
  (let [cfg (cfg->map cfg-pars)]
    (if-not cfg
      (println
        "Command line parmaters must be supplied as keyword/value pairs:\n"
        "for example:  :port 9999 :local-repl true")
    (let [ok (start cfg)]
      (print "The standalone ringMon ")
        (if ok
          (println (str "is up and running using port "
                     (:port @loc-cfg)"."))
          (println "has failed to start."))))))
