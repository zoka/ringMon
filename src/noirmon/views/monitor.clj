(ns noirmon.views.monitor
  (:require [noir.response :as resp]
            [noir.request :as req]
            [clojure.java.jmx :as jmx])
  (:use [noir.core :only [defpage]]))


(def cpu-load (atom 0.0))
(def ^:const sample-interval 2000) ; msec

(defn get-process-nanos []
  (jmx/read "java.lang:type=OperatingSystem" :ProcessCpuTime))

(defn calc-cpu-load [cpu-time clock-time]
  (/ (* 100.0 cpu-time) clock-time))

(defn cpu-load-sampler []
  (loop [process-nanos     (get-process-nanos)
         real-nanos        (System/nanoTime)
         old-process-nanos 0
         old-real-nanos    0]

         (Thread/sleep sample-interval)
         (reset! cpu-load 
                 (calc-cpu-load
                   (- process-nanos old-process-nanos)
                   (- real-nanos old-real-nanos)))
         (recur (get-process-nanos)
                (System/nanoTime)
                process-nanos
                real-nanos)))

(defn init []
  ; kick off endless cpu-sampler thread
  ; has to be called from noirmon.server/-main
  (.start (Thread. cpu-load-sampler)))

; return CPU load in JMX format (as a map)
(defn get-cpu-load[]
  {:CpuLoad (format "%5.2f%%" @cpu-load) })

(defn get-mon-data []
    (let [cpu (get-cpu-load)
          os  (jmx/mbean "java.lang:type=OperatingSystem")
          mem (jmx/mbean "java.lang:type=Memory")
          th  (dissoc (jmx/mbean "java.lang:type=Threading") :AllThreadIds)]
          {:Application cpu :OperatingSystem os :Memory mem :Threading th}))
  
(defn do-jvm-gc []
  (jmx/invoke "java.lang:type=Memory" :gc)
  {:resp "ok"})

(defn decode-cmd [request]
  (let [cmd (keyword (:cmd request))]
    (case cmd
      :get-mon-data (get-mon-data)
      :do-jvm-gc (do-jvm-gc)
      {:resp "bad-cmd"})))


(defpage "/admin/monview" []
  (resp/redirect "/admin/monview.html"))

(defpage [:get "/admin/moncmd"] {:as params}
  (let [reply (decode-cmd params)]
    (resp/json reply)))



