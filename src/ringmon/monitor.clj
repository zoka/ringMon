(ns ringmon.monitor
  (:require
    [clojure.string            :as string]
    [ringmon.json              :as json]
    [ring.middleware.resource  :as res]
    [ring.middleware.params    :as param]
    [ring.middleware.file-info :as finfo]
    [ring.util.response        :as response]
    [ringmon.nrepl             :as repl]
    [ringmon.cookies           :as cookies]
    [clojure.java.jmx          :as jmx]))

(def tmancy "◔_◔")

; the middleware configuration
(def the-cfg (atom
  {
   :local-repl   nil    ; set to true if browser is to autostart assuming
                        ; running locally (optional).
   :http-server  nil    ; Ring compatible http server start function that
                        ; needs ring-handler and {:port port} as parameters
                        ; If it is not set, the Jetty one will be attempted
                        ; to be resolved dynamically
   :ring-handler nil    ; Ring compatible handler
   :port         nil    ; http-server port
   ; If ringMon is to use an http server other than Jetty
   ; then :http-server key value needs to be set prior to calling
   ; the ringmon.server/start
   ;---------------------------------------------------------------------
   ; Browser parameters
   :fast-poll  500      ; browser poll when there is a REPL output activity
   :norm-poll  2000     ; normal browser poll time
   :parent-url ""       ; complete url of the parent application main page (optional)
   :lein-webrepl nil    ; set if runing in standalone mode in context of the
                        ; lein-webrepl plugin. May be used to customize the
                        ; browser behaviour. Also used by nREPL server side
                        ; to merge :main and :repl-init key values from project.clj
   ;-----------------------------------------------------------------------
   ; access control
   :disabled   nil      ; general disable, if true then check :the auth-fn
   :auth-fn    nil}))   ; authorisation callback, checked only if :disabled is true
                        ; will be passed a Ring request, return true if Ok

(def cpu-load      (atom 0.0))
(def ajax-reqs-ps  (atom 0.0))   ; ajax requests per second
(def ajax-reqs-tot (atom 0))     ; total requests
(def ^:const sample-interval 2000) ; msec

(defn get-process-nanos
  []
  (jmx/read "java.lang:type=OperatingSystem" :ProcessCpuTime))

(defn calc-cpu-load
  [cpu-time clock-time]
  (/ (* 100.0 cpu-time) clock-time))

(defn data-sampler
  []

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
         (repl/check-sessions) ; sessions house-keeping
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
  (select-keys @the-cfg [:fast-poll
                         :norm-poll
                         :parent-url
                         :lein-webrepl]))

(defn get-mon-data
  [sname client-ip ring-sess]
  (let [os  (jmx/mbean "java.lang:type=OperatingSystem")
        mem (jmx/mbean "java.lang:type=Memory")
        ; java.jmx returns Java arrays which json parser can not handle
        ; and thread id values are not interesting anyway
        th  (dissoc (jmx/mbean "java.lang:type=Threading") :AllThreadIds)
        ; fetch relevant session info
        sid  (repl/get-sess-id sname client-ip ring-sess) ; convert internal browser name to sid
        repl (repl/do-poll sid)
        sessions (repl/session-stats)
        msg  (repl/fetch-session-msg-top sid)      ; append to top window
        rb   (repl/fetch-session-buf-bottom sid)]  ; replace the entrire bottom  buffer

        {:Application
          {:CpuLoad          (format "%5.2f%%" @cpu-load)
           :AjaxReqsTotal    @ajax-reqs-tot
           :AjaxReqsPerSec   (format "%7.2f" @ajax-reqs-ps)}
         :LeinProject        (repl/get-lein-project)
         :JMX
           {:OperatingSystem os
            :Memory          mem
            :Threading       th}
         :nREPL            repl    ; nREPL must be before since it carries sid
         :ReplSessions     sessions
         :_replBuf         rb    ;remote update for REPL input buffer (init conn, invites)
         :_chatMsg         msg
         :_config          (extract-config)}))

(defn do-jvm-gc
  []
  (jmx/invoke "java.lang:type=Memory" :gc)
  {:resp "ok"})

(defn send-chat
  [sname msg to client-ip ring-sess]
  (repl/send-chat-msg sname msg to client-ip ring-sess )
  {:resp "ok"})

(defn set-chat-nick
  [sname nick client-ip ring-sess]
  (let [old-nick (repl/set-chat-nick sname nick client-ip ring-sess)]
    {:resp "ok" :old-nick old-nick}))

(def ringmon-host-url (atom nil))

(defn gen-invite
  [sname to from msg sid client-ip]
  (let [[name invite-pars] 
         (repl/register-invite sname to from msg client-ip)]
    { :resp "ok"
      :name name
      :url 
       (str @ringmon-host-url
            "/ringmon/monview.html"
            invite-pars)}))

(defn check-for-invite
  [params ring-sess]
  )

(defn get-host-url
  [req]
  (let [srv  (:server-name req)
        port (:server-port req)
        tp   (name(:scheme req))]
    (str tp "://" srv ":" port)))

(defn init-module
  []
  (.start (Thread. data-sampler))
  (repl/set-mirror-cfg @the-cfg)
  (repl/parse-lein-project))

(def sampler-started    (atom 0))

(defn decode-cmd
  [params client-ip ring-sess]
  (when (compare-and-set! sampler-started 0 1)
    (init-module))
  (let [cmd   (keyword (:cmd params))
        sname (:sname params)]
    (swap! ajax-reqs-tot inc)
    (case cmd
      :get-mon-data  
        (get-mon-data sname client-ip ring-sess)
      :do-jvm-gc     
        (do-jvm-gc)
      :do-repl       
        (repl/submit-form sname (:code params) client-ip ring-sess )
      :repl-break    
        (repl/break sname client-ip ring-sess)
      :send-chat     
        (send-chat sname (:msg params) (:to params) client-ip ring-sess)
      :set-chat-nick 
        (set-chat-nick sname (:nick params) client-ip ring-sess)
      :gen-invite
        (gen-invite sname (:to params) (:from params) (:msg params) 
                           client-ip ring-sess)
      {:resp "bad-cmd"})))

(defn ajax
  [params client-ip ring-sess]
  (let [reply    (decode-cmd params client-ip ring-sess)
        j-reply  (json/generate-string reply)]
    j-reply))

(defn get-client-ip
  [req]
  (let [hdrs  (:headers req)
        xfwd  (get hdrs "x-forwarded-for")]
    (if xfwd
      (first (string/split xfwd #","))
      (:remote-addr req))))

(defn ringmon-req?
  [uri]
  (or (= uri "/ringmon/command")
      (= uri "/ringmon/monview.html")))

(defn ringmon-allowed?
  [req]
  (if (:disabled @the-cfg)
    (if-let [auth-fn (:auth-fn @the-cfg)]
      (if (fn? auth-fn)
        (auth-fn req)
        nil)
      nil)
    true))

(defn wrap-ajax
  [handler]
  (fn [req]
    (let [uri (:uri req)]
      (if (ringmon-req? uri)
        (if (ringmon-allowed? req)
          (let [params (clojure.walk/keywordize-keys (:query-params req))
                client-ip (get-client-ip req)
                ring-sess (get
                            (get 
                              (:cookies req) "ring-session") :value)]
            (when-not @ringmon-host-url
              (reset! ringmon-host-url (get-host-url req)))
            (if (= uri "/ringmon/command")
              (response/response (ajax params client-ip ring-sess))
              (do
                (when params
                  (check-for-invite params ring-sess))
                (handler req))))
          (response/response "Not allowed"))
        (handler req)))))

(defn wrap-pass-through
  [handler]
  (fn [req]
    (let [uri (:uri req)]
      (if (ringmon-req? uri)
        (response/response
          (str "ringMon already wrapped into separate web server at port "
            (:port @the-cfg)))
        (handler req)))))

(defn wrap-ring-monitor
  [handler]

  (if (:local-repl @the-cfg)
    (-> handler
        (wrap-pass-through))

    (-> handler
        (res/wrap-resource "public")
        (finfo/wrap-file-info)
        (wrap-ajax)
        (wrap-gzip)                   ; gzip must be after wrap-resource!
        (cookies/wrap-noir-cookies)
        (param/wrap-params))))

(defn merge-cfg
  [cfg]
  (when (map? cfg)
    (swap! the-cfg merge cfg)
    (repl/set-mirror-cfg @the-cfg))) ; make sure the mirror is up to date

