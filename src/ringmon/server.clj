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
         :body (str
         "<head>
            <title>ringMon Demo</title>
          </head>
          <body>
          <p>
            This simple page demonstrates usage of ringMon middleware.
          </p>
          <p>
            Once activated, the middleware injects the
            <a target=\"_blank\" href=\"/ringmon/monview.html\">monitoring page</a>
            into existing Ring based web application.
           </p>
           <p>
            In this particular case the page url is:
            <a target=\"_blank\" href=\"http://" hostname
            "/ringmon/monview.html\">http://"hostname"/ringmon/monview.html</a>
          </p>
          <p>
            The injected page provides
            facility to periodicaly display important JMX and derived application
            runtime data, such as CPU load, memory usage, thread count etc.
            Moreover, it provides nREPL inerface to the live application
            instance.
          <p>
          </body>")}
         {:status 404
         :headers {"Content-Type" "text/html"}
         :body (str "The uri:" uri " was not found on this server.")})))

(def handler
  (-> demo
      (monitor/wrap-ring-monitor)))

(defn -main [& m]
  (let [mode (keyword (or (first m) :dev))
        port (Integer. (get (System/getenv) "PORT" "8080"))]

    (println "The ringMon local demo starting...")
    (jetty/run-jetty handler {:port port})))

