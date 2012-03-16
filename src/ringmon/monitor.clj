(ns ringmon.monitor
  (:require
    [clojure.string            :as string]
    [clj-json.core             :as json]
    [ring.middleware.resource  :as res]
    [ring.middleware.params    :as param]
    [ring.middleware.file-info :as finfo]
    [ring.util.response        :as response]
    [ringmon.nrepl             :as repl]
    [ringmon.cookies           :as cookies]
    [clojure.java.jmx          :as jmx]))

(def the-cfg (atom {:fast-poll 500
                    :norm-poll 2000}))   ; middleware config

(def sampler-started    (atom false))
(def cpu-load      (atom 0.0))
(def ajax-reqs-ps  (atom 0.0))   ; ajax requests per second
(def ajax-reqs-tot (atom 0))     ; total requests

(def last-request (atom {}))

(def ^:const sample-interval 2000) ; msec

(defn get-process-nanos
  []
  (jmx/read "java.lang:type=OperatingSystem" :ProcessCpuTime))

(defn calc-cpu-load
  [cpu-time clock-time]
  (/ (* 100.0 cpu-time) clock-time))

(defn data-sampler
  []
  (reset! sampler-started true)
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

; based on https://github.com/mikejs/ring-gzip-middleware.git
; just converted to use clojure.java.io from Clojure 1.3
(defn gzipped-response
  [resp]
  (let [body (resp :body)
        bout (java.io.ByteArrayOutputStream.)
        out (java.util.zip.GZIPOutputStream. bout)
        resp (assoc-in resp [:headers "content-encoding"] "gzip")]
    (clojure.java.io/copy body out)
    (.close out)
    (if (instance? java.io.InputStream body)
      (.close body))
    (assoc resp :body (java.io.ByteArrayInputStream. (.toByteArray bout)))))

(defn wrap-gzip
  [handler]
  (fn [req]
    (let [{body :body
           status :status
           :as resp} (handler req)]
      (if (and (= status 200)
               (not (get-in resp [:headers "content-encoding"]))
               (or
                (and (string? body) (> (count body) 200))
                (instance? java.io.InputStream body)
                (instance? java.io.File body)))
        (let [accepts (get-in req [:headers "accept-encoding"] "")
              match (re-find #"(gzip|\*)(;q=((0|1)(.\d+)?))?" accepts)]
          (if (and match (not (contains? #{"0" "0.0" "0.00" "0.000"}
                                         (match 3))))
            (gzipped-response resp)
            resp))
        resp))))

(defn extract-config
  []
  (select-keys @the-cfg [:fast-poll :norm-poll]))

(defn get-mon-data
  [sname client-ip]
  (let [os  (jmx/mbean "java.lang:type=OperatingSystem")
        mem (jmx/mbean "java.lang:type=Memory")
        ; java.jmx returns Java arrays which json parser can not handle
        ; and thread id values are not interesting anyway
        th  (dissoc (jmx/mbean "java.lang:type=Threading") :AllThreadIds)
        sessions (repl/session-stats)
        repl (repl/do-cmd "" sname client-ip)]

        {:Application
          {:CpuLoad           (format "%5.2f%%" @cpu-load)
           :AjaxReqsTotal     @ajax-reqs-tot
           :AjaxReqsPerSec    (format "%7.2f" @ajax-reqs-ps)}
          :OperatingSystem   os
          :Memory            mem
          :Threading         th
          :Sessions          sessions
          :nREPL repl
          :config (extract-config)}))

(defn do-jvm-gc
  []
  (jmx/invoke "java.lang:type=Memory" :gc)
  {:resp "ok"})

(defn decode-cmd
  [request client-ip]
  (when-not @sampler-started
    (.start (Thread. data-sampler)))
  (let [cmd (keyword (:cmd request))]
    (swap! ajax-reqs-tot inc)
    (case cmd
      :get-mon-data (get-mon-data (:sess request) client-ip)
      :do-jvm-gc    (do-jvm-gc)
      :do-repl      (repl/do-cmd (:code request) (:sess request) client-ip)
      :repl-break   (repl/break  (:sess request) client-ip)
      {:resp "bad-cmd"})))

(defn ajax
  [params client-ip]
  (let [reply    (decode-cmd params client-ip)
        j-reply  (json/generate-string reply)]
    j-reply))

(defn get-client-ip
  [req]
  (let [hdrs  (:headers req)
        xfwd  (get hdrs "x-forwarded-for")]
    (if xfwd
      (first (string/split xfwd #","))
      (:remote-addr req))))

(defn wrap-ajax
  [handler]
  (fn [req]
    (let [uri (:uri req)]
      (if (= uri "/ringmon/command")
        (let [params (clojure.walk/keywordize-keys (:query-params req))
             client-ip (get-client-ip req)]
          (reset! last-request req) ; for debugging, is easy do read from REPL
          (response/response(ajax params client-ip)))
        (handler req)))))

(defn wrap-ring-monitor
  [handler]

  (if (:local-repl @the-cfg)
    handler    ;; no need to wrap, it was already wraped into local jetty server

    (-> handler
        (res/wrap-resource "public")
        (finfo/wrap-file-info)
        (wrap-gzip)                   ; gzip must be after wrap-resource!
        (wrap-ajax)
        (cookies/wrap-noir-cookies)
        (param/wrap-params))))

(defn merge-cfg
  [cfg]
  (when (map? cfg)
    (swap! the-cfg merge cfg)))
