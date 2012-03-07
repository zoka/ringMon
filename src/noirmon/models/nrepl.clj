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

; transient session
(defn handle-transient [req]
  (with-open [conn (connect)]
    (-> (repl/client conn 5000)
      (repl/message req)
      doall)))

(def sessions (atom {}))  ;; active sessions map

(defn get-sess-count
  []
  (let [k (keys @sessions)]
    (count k)))


(defprotocol SessionOps
  (poll [this]
    "Reads and returns accumulated session output. Can return nil
     if no output is pending.")
  (put [this cmd]      "Send command to the session instance.")
  (get-id [this]       "Get session id.")
  (get-pending [this]  "Get the id of the currently pending command or nil."))

(deftype FnSession [poll-fn put-fn get-id-fn get-pending-fn]
  SessionOps
  (poll [this]        (poll-fn))
  (put  [this cmd]    (put-fn cmd))
  (get-id [this]      (get-id-fn))
  (get-pending [this] (get-pending-fn)))


(defn peek-q
  [q]
  (let [e (peek q)]
    (get e 0)))  ; return cid field only

(defn dump-q
  [q]
  (loop [n (count q) i 0]
    (if (zero? n)
      i
      (let [e (nth q i)]
        (println i"=" (get e 0))
        (println (get e 1))
        (recur (dec n) (inc i))))))

(defn session-instance
  [timeout]
  (let [conn     (connect)
        client   (repl/client conn timeout) ; short timeout requirea (100 ms)
        sid      (repl/new-session client)
        client-msg (repl/client-session client :session sid)
        cmdQ     (atom (clojure.lang.PersistentQueue/EMPTY))
        last-e (atom nil)
        do-resp (fn [resp] ; response is sequence of maps
                  "Take command response in and return it back with
                   appropriate pending indication. Examine every
                   status field in response and remove 'done' commands
                   from the pending queue head."
                  (loop [r resp pid (peek-q @cmdQ)]
                    (if (empty? r)
                      (conj resp {:pend (not (nil? pid))})
                      (let [ e (first r)
                             s (:status e)]
                        (when s
                          (when (not= -1 (.indexOf s "done")) ; "done" is part of status?
                            (if (= e @last-e)        ; spmetimed we get duplicate status messages
                              nil                          ; just recur (nil is placeholder of error handling)
                              (let [cid (:id e)]           ; else continue
                                (reset! last-e e)
                                (if-not pid
                                  nil
                                  (if (= cid pid)
                                    (swap! cmdQ pop)
                                    nil ))))))
                        (recur (next r) (peek-q @cmdQ))))))]
    (FnSession.
      (fn poll []
        (do-resp (client)))
      (fn put [cmd]
        (let [cid (repl.misc/uuid)]
              ; attach command and session ids to original command
              ; before submitting it to nREPL server
              (client-msg (assoc cmd :session sid :id cid))
              (when-not (= (:op cmd) :interrupt)
                (swap! cmdQ conj [cid cmd])); put in the Q if it is not interrupt command
              ;(println "submitted:" (assoc cmd :session sid :id cid))
              (let [r (client)]
                (if (empty? r)
                  [{:id cid} :pend true] ; if no immediate response in 100 ms,
                                         ; just return command id and pending indication
                  (do-resp r)            ; else return nREPL response,
                                         ; containing command id anyway
                ))))
      (fn get-id []
        sid)
      (fn get-pending []
        (peek-q @cmdQ)))))

(defn active-session?
  [sid]
  (let [s (get @sessions sid)
        r (not= s nil)]
    r))

(defn init-session
  []
  "note: the low timeout value of 100ms for session means
   that client will not stay blocked after submitting a
   long duration command such as '(Thread/sleep 1000)'"
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
        ;(println "new session map id (valid 30 days):" cval sname)
        (cookies/put! :repl-sess  {:value cval
                                   :path "/admin"
                                   :max-age session-age})
        new-sid)
      sid)))

(defn do-cmd
  [code sname]
  (let [sid (get-sess-id sname)]
    (when-not (= sid "")
      (if (not= code "")
        (let [r (session-put sid {:op :eval :code code})]
          ;(println "put response:"r)
          r)
        (let [r (session-poll sid)]
          ;(when-not (empty? r) (println "bkg polled:" r))
          r)))))

(defn do-transient-repl
  [code]
  (let [r  (handle-transient {:op :eval :code code})]
    r))

(defn break
  [sname]
  (let [sid (get-sess-id sname)]
    (when-not (= sid "")
      (let [s (get @sessions sid)]
        (when s
          (let [cid (get-pending s)]
            ;(println "requesting break for" cid)
            (if cid
              (let [r (put s {:op :interrupt :interrupt-id cid})]
                ;(println "break response:" r)
                r)
              (println "break: no command pending"))))))))

