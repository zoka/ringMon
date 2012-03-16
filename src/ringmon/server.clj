(ns ringmon.server
  (:require
      [ringmon.monitor          :as monitor]
      [ring.util.response       :as resp]
      [ring.adapter.jetty       :as jetty])
  (:import (java.net Socket ServerSocket URI)
           (java.awt Desktop)))

(def loc-cfg (atom {:local-port 8081
                    :fast-poll  200
                    :norm-poll  500}))

(defn default [req]
  (resp/redirect "/ringmon/monview.html"))

(def handler
  (-> default
      (monitor/wrap-ring-monitor)))

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

(defn get-port
  []
  (when (:local-repl @loc-cfg)
    (let [port (:local-port @loc-cfg)]
      (when (= port 0)
        (let [ss (ServerSocket. 0)
              port (.getLocalPort ss)]
          (swap! loc-cfg assoc :local-port port)
          (.close ss)))))
  (:local-port @loc-cfg))

(defn start
  [& cfg]
  (let [cfg (first cfg)] ;only on parameter expected
    (when (or (nil? cfg) (map? cfg))
      (swap! loc-cfg merge cfg)
      (let [port (get-port)]
        (println "The ringMon local instance starting with config" @loc-cfg)
        (future
          (monitor/merge-cfg @loc-cfg)
          (jetty/run-jetty handler {:port port}))

        (Thread/sleep 100) ; allow some time for Jetty to start
        (when (:local-repl @loc-cfg)
          (start-browser (str"http://localhost:"
                          (str (:local-port @loc-cfg))
                          "/ringmon/monview.html")))))))
(defn -main
  [& cfg-pars]
  (let [cfg-str (first cfg-pars)]
    (if cfg-str
      (try
        (let [cfg (read-string cfg-str)]
          (if-not (map? cfg)
            (start {})
            (start cfg)))
        (catch Exception e
          (println "Invalid configuration map\nException:" e)
          (Thread/sleep 100))) ; let exception print out in peace
      (start))))



