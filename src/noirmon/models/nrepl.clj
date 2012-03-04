(ns noirmon.models.nrepl
  (:require [clojure.tools.nrepl.server    :as server]
            [clojure.tools.nrepl.misc      :as misc]
            [clojure.tools.nrepl.transport :as t]
            [clojure.tools.nrepl           :as repl]
            [noir.cookies                  :as cookies]
            [clojure.tools.nrepl.misc      :as repl.misc]
            [cheshire.core                 :as json]
            (clojure walk))
  (:import  (clojure.tools.nrepl.transport FnTransport)
             (java.util.concurrent
                LinkedBlockingQueue
                TimeUnit)))

(defn set-to-vec
 "Convert set containing keywords into
  vector of strings."
  [s]
  (let [v (map #(name %1) s)]
    (vec v)))

(defn patch-from-srv-msg
 "Patch message sent by nREPL server before it gets
  delivered to client.
  First keywordize everything.
  Second - the status comes back as a set and needs to be
  converted to vector and all keywords in it
  to strings, since bencoder seems to be
  doing the same thing"
  [msg]
  (let [k (clojure.walk/keywordize-keys msg)
        s (get k :status)]

    ;(println "srv-> original:" msg)
    (if s
      (let [p (assoc k :status (set-to-vec s))]
        ;(println "srv-> patched:" p)
        p)
      (do
        ;(println "srv-> patched:" k)
        k))))

(defn patch-from-client-msg
 "Patch message sent by client
  before it gets delivered to nREPL server.
  If there are any map values that are keywords
  convert them to plain strings, so they can be
  properly interpreted on server side."
  [msg]
  (let [p (into {}
    (for [[k v]  msg] [ k (#(name %1) v) ]))]
     ;(println "clnt-> original:" msg)
     ;(println "clnt-> patched:" p)
    p))

(defn transport-pair
 "Returns vector of 2 direct transport instances,
  first one for client, and second one for server.
  It is assumed that only pair of threads are waiting
  on this transport pair - so in case of close operation
  it is safe for the client or server thread to clear its
  incoming queue without adverse impact."
  []
  (let [client-q (LinkedBlockingQueue.)
       server-q  (LinkedBlockingQueue.)
       open (atom true)  ; all these variables remain in closure
                         ; so they hold instance state in a safe place

       client-transport (FnTransport.
         (fn read [timeout]
           (if @open
             (let [data (.poll server-q timeout TimeUnit/MILLISECONDS)]
               (when-not @open
                 (.clear server-q)) ; clear q if it was closed while we were blocked in poll
               (patch-from-srv-msg data))))
         (fn write [data]
           (when @open
             (.put client-q data)))
         (fn close []
           (reset! open false)
           (.clear server-q)))  ; safe to clear incomming queue

       server-transport (FnTransport.
         (fn read [timeout]
           (if @open
             (let [data (.poll client-q timeout TimeUnit/MILLISECONDS)]
               (when-not @open
                 (.clear client-q))
               (patch-from-client-msg data))))
         (fn write [data]
           (when @open
             (.put server-q data)))
         (fn close []
           (reset! open false)
           (.clear client-q)))]  ; safe to clear incomming queue
  [client-transport server-transport]))

(defn handle
  "Handles requests received via `transport` using `handler`.
   Returns nil when `recv` returns nil for the given transport."
  [handler transport]
  (when-let [msg (t/recv transport)]
    (try
      (or (handler (assoc msg :transport transport))
          (server/unknown-op transport msg))
      (catch Throwable t
        (println "Unhandled REPL handler exception processing message" msg)))
    (recur handler transport)))


(defn connect
  "Connects to an REPL within the same procees using
   pair of LinkedBlockedQueue instances.
   There is no server here, just a
   furure that handles one to one connection.
   Returns client transport instance."
  []
  (let [ [client-t server-t] (transport-pair) ]
    (future (with-open
              [transport server-t
               handler   (server/default-handler)]
              (server/handle handler transport)))
    client-t))

;; transient session
(defn handle-transient [req]
  (println "orig request:" req)
  (with-open [conn (connect)]
    (-> (repl/client conn 5000)
      (repl/message req)
      doall)))

(def sessions (atom {}))  ;; active sessions map

(defprotocol SessionOps
  (poll [this] 
    "Reads and returns accumulated session output. Can return nil
     if no output is pending.")
  (put [this cmd] "Sends command to the session instance.")
  (get-id [this] "Gets session id"))

(deftype FnSession [poll-fn put-fn get-id-fn]
  SessionOps
  (poll [this]        (poll-fn))
  (put  [this cmd]    (put-fn cmd))
  (get-id [this]      (get-id-fn)))

(defn get-new-session
  [client conn]
  ;(println "client:" client "\nconn:" conn) (Thread/sleep 1000)
  (repl/new-session client))

(defn session-instance [timeout]
  (let [conn     (connect)
        client   (repl/client conn timeout) ; short timeout makes it snappy
        sid      (get-new-session client conn)
        client-msg (repl/client-session client :session sid)]

    (FnSession.
      (fn poll []
        (doall (client)))
      (fn put [cmd]
        (let [cid (repl.misc/uuid)]
          (client-msg (assoc cmd :session sid :id cid))))
      (fn get-id []
        sid))))

(defn active-session?
  [sid]
  (let [s (get @sessions sid)
        r (not= s nil)]
    ;(println "checking sid " sid r s)
    r))

(defn init-session
  []
  (let [s (session-instance 100)]
    (when s
      (swap! sessions assoc (get-id s) s))
        (get-id s)))

(defn session-put
  [sid cmd]
  (let [s (get @sessions sid)]
    (when s
      (put s cmd))))

(defn session-poll
  [sid]
  (let [s (get @sessions sid)]
    (when s
      (poll s))))

(def ^:const session-age (str (* 3600 24 30)))

(defn get-active-browser-sessions
  []
   (let [s (cookies/get :repl-sess)
         m (json/parse-string s)]
    (if-not (map? m)
      {}
      (loop [r {} m m]
        (if (empty? m)
          r
          (let [[name sid] (first m)]
            (if (active-session? sid)
              (recur (assoc r name sid) (rest m))
              (recur r (rest m)))))))))
 
(defn get-sess-id
  [sname]
  (let [as  (get-active-browser-sessions)
        sid (get as sname)]
    (if-not sid
      (let [new-sid (init-session)
            new-as  (assoc as sname new-sid)
            cval (json/generate-string new-as)]
        ;(println "new map id (valid 30 days):" cval sname)
        (cookies/put! :repl-sess  {:value cval 
                                   :path "/admin"
                                   :max-age session-age})
        new-sid)
      sid)))

(defn do-cmd
  [code sname]
  (let [sid (get-sess-id sname)]
    (if-not (= sid "")
      (if (not= code "")
        (let [r (session-put sid {:op :eval :code code})]
          (println "put response:"r)
          r)
        (let [r (session-poll sid)]
          (when-not (empty? r)
            (println "bkg polled:" r))
          r)))))
    
(defn do-transient-repl
  [code]
  (let [r  (handle-transient {:op :eval :code code})]
    ;(println "\ndo-transient-repl:" r)
    r))




