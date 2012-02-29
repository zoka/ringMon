(ns noirmon.views.monitor
  (:require [noir.response :as resp]
            [noir.request :as req]
            [noirmon.models.nrepl :as repl]
            [clojure.java.jmx :as jmx])
  (:use [noir.core :only [defpage]]))


(def cpu-load      (atom 0.0))
(def ajax-reqs-ps  (atom 0.0))     ; ajax requests per second
(def ajax-reqs-tot (atom 0))     ; total requests
  
(def ^:const sample-interval 2000) ; msec

(defn get-process-nanos []
  (jmx/read "java.lang:type=OperatingSystem" :ProcessCpuTime))

(defn calc-cpu-load [cpu-time clock-time]
  (/ (* 100.0 cpu-time) clock-time))

(defn data-sampler []
  (loop [process-nanos     (get-process-nanos)
         real-nanos        (System/nanoTime)
         ajax-reqs         @ajax-reqs-tot
         old-process-nanos 0
         old-real-nanos    0
         old-ajax-reqs     0]

         (Thread/sleep sample-interval)
         (reset! cpu-load 
                 (calc-cpu-load
                   (- process-nanos old-process-nanos)
                   (- real-nanos old-real-nanos)))
         
         (reset! ajax-reqs-ps 
                 (/ (- ajax-reqs old-ajax-reqs) 2.0))
      
         (recur (get-process-nanos)
                (System/nanoTime)
                @ajax-reqs-tot
                process-nanos
                real-nanos
                ajax-reqs)))

(defn init []
  ; kick off endless data-sampler thread
  ; has to be called from noirmon.server/-main
  (.start (Thread. data-sampler))
  ) ; init nrepl  
   

(defn get-mon-data []
  (let [os  (jmx/mbean "java.lang:type=OperatingSystem")
        mem (jmx/mbean "java.lang:type=Memory")
        ; java.jmx returns Java arrays which repl/json can not handle
        ; and thread id values are not interesting anyway
        th  (dissoc (jmx/mbean "java.lang:type=Threading") :AllThreadIds)]
        
        {:Application   
          {:CpuLoad         (format "%5.2f%%" @cpu-load)
          :AjaxReqsTotal   @ajax-reqs-tot 
          :AjaxReqsPerSec  (format "%7.2f" @ajax-reqs-ps)}
          :OperatingSystem os 
          :Memory          mem
          :Threading       th}))

(defn do-jvm-gc []
  (jmx/invoke "java.lang:type=Memory" :gc)
  {:resp "ok"})

(defn do-repl [code]
  (let [r (repl/handle-transient {:op "eval" :code code})] 
    (println "do-repl:" r)
    r))

(defn decode-cmd [request]
  (let [cmd (keyword (:cmd request))]
    (swap! ajax-reqs-tot inc)  
    (case cmd
      :get-mon-data (get-mon-data)
      :do-jvm-gc (do-jvm-gc)
      :do-repl (do-repl (:code request))
      {:resp "bad-cmd"})))


(defpage "/admin/monview" []
  (resp/redirect "/admin/monview.html"))

(defpage [:get "/admin/moncmd"] {:as params}
  (let [reply (decode-cmd params)]
    (resp/json reply)))



