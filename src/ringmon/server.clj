(ns ringmon.server
  (:require 
            [ringmon.monitor :as monitor]))


(defn -main [& m]
  (let [mode (keyword (or (first m) :dev))
        port (Integer. (get (System/getenv) "PORT" "8080"))]

;    (server/start port {:mode mode
;                        :ns 'noirmon})
    (println "Started")
    (monitor/init)))         ; has to be done after starting the server
                             ; (fails on Heroku otherwise)
  
