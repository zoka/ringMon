(ns noirmon.server
  (:require [noir.server :as server]
            [noirmon.views.monitor :as monitor]))

(server/load-views "src/noirmon/views/")

(defn -main [& m]
  (let [mode (keyword (or (first m) :dev))
        port (Integer. (get (System/getenv) "PORT" "8080"))]

    (monitor/add-gzip-middleware) ; must be done before starting the server
    (server/start port {:mode mode
                        :ns 'noirmon})
    (monitor/init)))         ; has to be done after starting the server
                             ; (fails on Heroku otherwise)

