(ns noirmon.server
  (:require [noir.server :as server]
            [noirmon.views.monitor :as monitor]))

(server/load-views "src/noirmon/views/")

(defn -main [& m]
  (let [mode (keyword (or (first m) :dev))
        port (Integer. (get (System/getenv) "PORT" "8080"))]
    (server/start port {:mode mode
                        :ns 'noirmon})
    (monitor/init)))

