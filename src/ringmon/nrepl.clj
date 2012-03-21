(ns ringmon.nrepl
  (:require [clojure.tools.nrepl.server    :as server]
            [clojure.tools.nrepl.misc      :as misc]
            [clojure.tools.nrepl.transport :as t]
            [clojure.tools.nrepl           :as repl]
            [clojure.string                :as string]
            [ringmon.cookies               :as cookies]
            [clojure.tools.nrepl.misc      :as repl.misc]
            [clj-json.core                 :as json]
            (clojure walk))
  (:import  (clojure.tools.nrepl.transport FnTransport)
            (java.util.concurrent LinkedBlockingQueue TimeUnit)
            (java.util Date)
            (java.text SimpleDateFormat)
            (java.net InetAddress)))

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
               (if @open
                 (patch-from-srv-msg data)
                 (do
                   ;(println "Client detected close")
                   (.clear server-q)
                   nil)))))
         (fn write [data]
           (when @open
             (.put client-q data)))
         (fn close []
           ;(println "Client close")
           (reset! open false)
           (.put client-q [ ]) ; unblock other side if needed
           (.clear server-q))) ; safe to clear incomming queue

       server-transport (FnTransport.
         (fn read [timeout]
           (if @open
             (let [data (.poll client-q timeout TimeUnit/MILLISECONDS)]
               (if @open
                 (patch-from-client-msg data)
                 (do
                   ;(println "Server detected close")
                   (.clear client-q)
                   nil)))))
         (fn write [data]
           (when @open
             (.put server-q data)))
         (fn close []
           ;(println "Server close")
           (reset! open false)
           (.put server-q [ ])  ; unblock other side if needed
           (.clear client-q)))] ; safe to clear incomming queue
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
              (server/handle handler transport)
              (.close transport)))
    client-t))

(defprotocol SessionOps
  (poll [this]
    "Reads and returns accumulated session output. Can return nil
     if no output is pending.")
  (put [this cmd]      "Send command to the session instance.")
  (get-id [this]       "Get session id.")
  (get-pending [this]  "Get the id of the currently pending command or nil.")
  (close [this]        "Close" )               )

(deftype FnSession [poll-fn put-fn get-id-fn get-pending-fn close-fn]
  SessionOps
  (poll [this]        (poll-fn))
  (put  [this cmd]    (put-fn cmd))
  (get-id [this]      (get-id-fn))
  (get-pending [this] (get-pending-fn))
  (close [this]       (close-fn)))


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

(defn my-new-session
 "This is a mirror of repl/new-session, so it can be observed why it sometimes
  throws an exception. When nil reply is received simply try again."
  [client]
  (loop [c 10]
    (let [resp (first (repl/message client {:op :clone}))
          r    (:new-session resp)]
      (if r
        r
        (if (zero? c)
          (do
            (println "Giving up on new-session")
            nil)
          (do
            ;(println "Retrying new-session," c "tries to go.")
            (Thread/sleep 10)
            (recur (dec c))))))))

(defn session-instance
  [timeout]
  (let [conn        (connect)
        client      (repl/client conn timeout) ; short timeout required (10 ms)
        sid         (my-new-session client)
        client-msg  (repl/client-session client :session sid)
        cmd-q       (atom (clojure.lang.PersistentQueue/EMPTY))
        last-e (atom nil)
        do-resp (fn [resp] ; response is sequence of maps
                  "Take command response in and return it back with
                   appropriate pending indication. Examine every
                   status field in response and remove 'done' command ids
                   from the pending queue head."
                  ;(println "resp " (count resp))
                  (loop [r resp pid (peek-q @cmd-q)]
                    (if (empty? r)
                      (conj resp {:sid sid :pend (not (nil? pid))}) ;; function return
                      (let [ e (first r)
                             s (:status e)]
                        (when s
                          (when (not= -1 (.indexOf s "done")) ; "done" is part of status?
                            (if (= e @last-e)        ; sometimes we get duplicate status messages
                              nil                          ; just recur (nil is placeholder of error handling)
                              (let [cid (:id e)]           ; else continue
                                (reset! last-e e)
                                (if-not pid
                                  nil
                                  (if (= cid pid)
                                    (swap! cmd-q pop)
                                    nil ))))))
                        (recur (next r) (peek-q @cmd-q))))))]
    (FnSession.
      (fn poll []
        (do-resp (client)))
      (fn put [cmd]
        (let [cid (repl.misc/uuid)]
              ; attach command and session ids to original command
              ; before submitting it to nREPL server
              (client-msg (assoc cmd :session sid :id cid))
              (when-not (= (:op cmd) :interrupt)
                (swap! cmd-q conj [cid cmd])); put in the Q if it is not interrupt command
              ;(println "submitted:" (assoc cmd :session sid :id cid))
              (let [r (client)]
                (if (empty? r)
                  [:pend true :cid cid] ; if no immediate response in 10 ms,
                                         ; just return command id and pending indication
                  (do-resp r)            ; else return nREPL response,
                                         ; containing command id anyway
                ))))
      (fn get-id []
        sid)
      (fn get-pending []
        (peek-q @cmd-q))
      (fn close []
        (let [cid (repl.misc/uuid)
              cmd {:cid cid :sessiom sid :op :close}]
          (client-msg cmd)
          (let [r (client)]
            ;(println "Close rep" r)
            (.close conn)))))))  ; important: deftype method is accessed as any Java class method

(defn first-line
  [s]
  (when s
    (first (string/split s #"\n"))))

(defn uuid-last
  [uuid]
  (when uuid
    (last (string/split uuid #"-"))))

(defprotocol SessionStats
  (get-stats [this sid] "Returns stats map"))

(defrecord SessionInfo [sess
                        sname
                        client-host
                        nick
                        last-code
                        last-req-time
                        last-cmd-time
                        total-ops
                        msg]
  SessionStats
  (get-stats [this sid]
    (let [now (System/currentTimeMillis)
          lc  (first-line last-code)]
      {:Client   client-host
       :SessName sname
       :SessId   sid
       :ChatNick nick
       :LastCode lc
       :DataReq  (format "%7.3f" (/ (- now last-req-time) 1000.0))
       :CmdReq   (format "%7.3f" (/ (- now last-cmd-time) 1000.0))
       :TotalOps total-ops})))

(def sessions (atom {}))  ;; active sessions map of sid to SessionInfo

(defn get-sess-count
  []
  (let [k (keys @sessions)]
    (count k)))

(defn session-stats
  []
  (let [svec (into []
        (for [[sid si] @sessions] (get-stats si sid)))]
     {:Count (get-sess-count)
      :Info svec}))

(defn check-session
  [sid]
  (when-let [si   (get @sessions sid)]
    (let [sess (:sess si)
          now (System/currentTimeMillis)]
      ; close the session if less than 5 requests in first 30 seconds
      (if (and (< (:total-ops si) 5)
               (> (- now (:last-req-time si)) 30000))
        (do
          (println "Closing dead session" sid)
          (swap! sessions dissoc sid)
          (close sess))
        nil))))

(defn check-sessions
  []
  (let [sids (keys @sessions)]
    (dorun (map check-session sids))))

(defn active-session?
  [sid]
  (let [s (get @sessions sid)
        r (not= s nil)]
    r))

(def user-no (atom 0))
(defn get-nick
  []
  (let [n (swap! user-no inc)]
    (str "clojurian-" n)))

(defn welcome-msg
  [nick]
  (str "Welcome to nREPL. Your chat nick is '" nick
    "'.\nTo change it, uncheck 'Confirm', modify 'Chat as' and check 'Confirm' again.
Just for fun, same can be done with this two liner:

(use 'ringmon.api)         ; just paste it into nREPL input window below,
(set-nick \"your-new-nick\") ; adjust the nick and press the 'Execute' button.

More chat functions are avaliable in ringmon.api namespace:
get-nick[]              ; get your nick
chat-nicks[]            ; get vector of all active nicks
send-chat [msg & nicks] ; send message to all or some

The nREPL input window bellow may contain a sample Clojure snippet.
To get it out of the way, press Ctrl-Down while you have it in focus.
To recall it back from history use Ctrl-Up."))

(defn init-session
  [client-ip sname]
 "note: the low timeout value of 10 ms for session means
  that client will not stay blocked after submitting a
  long duration command such as '(Thread/sleep 1000)'"
  (let [s   (session-instance 10)]
    (when (and s (get-id s))
      (let [now  (System/currentTimeMillis)
            ia   (InetAddress/getByName client-ip)
            rc   (.getCanonicalHostName ia)
            nick (get-nick)
            si   (SessionInfo. s sname rc nick
                             " \n" now now 0 (welcome-msg nick))]
        (swap! sessions assoc (get-id s) si))
          ;(println "init-session:" (get-id s) "\n" @sessions)
          (get-id s))))

(defn session-get-nick
  [sid]
  (let [si (get @sessions sid)]
    (when si
      (:nick si))))

(defn chat-nicks
  []
  (into [] (for [[sid si] @sessions] (:nick si))))

(defn session-append-msg
[sid msg]
(let [s (:sess (get @sessions sid))]
  (when s
    (locking s
      (let [si       (get @sessions sid)
            old-msg  (:msg si)]
        (if (= old-msg "")
          (swap! sessions assoc sid (assoc si :msg msg))
          (swap! sessions assoc sid (assoc si :msg (str old-msg "\n" msg)))))))))

(def date-format (SimpleDateFormat. "HH:mm:ss"))
(defn time-now
  []
  (.format date-format (Date.)))

(defn nicks-to-str
  [nicks]
  (loop [r "" nicks nicks count 0]
    (if (empty? nicks)
      (let [r (string/trim r)]
        (if (= count 1)
          r ; no brackets for single item
          (str "("r")")))
      (let [nick (first nicks)]
        (recur (str r " " nick) (disj nicks nick) (inc count))))))

(defn send-chat
  [sid msg nicks]
  (when (and sid msg (not (string/blank? msg )))
    (let [my-nick  (session-get-nick sid)
          nicks (disj (into #{} nicks) my-nick)] ; do not send message to self
      (when my-nick
        (if (empty? nicks)
          (let [m (str (time-now) " "my-nick": " msg) ; send to all
                k (keys @sessions)]
            (dorun (map #(session-append-msg %1 m) k)))
          (let [to-send (disj (into #{}
                 (for [[sid si] @sessions]
                   (when (contains? nicks (:nick si)) sid))) nil)
                to-nicks (disj (into #{}
                  (for [sd to-send] (session-get-nick sd))) nil)
                to-list (nicks-to-str to-nicks)
                m (str (time-now) " "my-nick"=>"to-list": " msg)]
            (when-not (empty? to-send)
              (session-append-msg sid m) ; message to self with recipents list
              (loop [sids to-send]
                (if (empty? sids)
                  true
                  (let [to-sid (first sids)
                        nick   (session-get-nick to-sid)
                        m      (str (time-now) " "my-nick"=>you: " msg)]
                    (session-append-msg to-sid m)
                    (recur (disj sids to-sid))))))))))))

(defn ensure-no-inner-space-or-col
  [s]
  (let [s (string/trim s)
        t (.replaceAll s " " "-")
        r (.replaceAll t ":" "-")]
     r))

(defn session-set-nick
 [sid nick]
 (let [my-si (get @sessions sid)]
  (when (and my-si (not (string/blank? nick)))
    (let [nick (ensure-no-inner-space-or-col nick)]
      (locking my-si
        (let [old     (:nick my-si)
              nicks   (into #{} (chat-nicks))]
          (if-not (contains? nicks nick)
            (do
              (swap! sessions assoc sid (assoc my-si :nick nick))
              (send-chat sid (str "Changed the nick from '" old "' to '" nick"'.") [])
              old)         ; return old nick if ok
            nil)))))))     ; clash, return nothing

(defn session-fetch-msg
  [sid]
  (let [s (:sess (get @sessions sid))]
    (when s
      (locking s
        (let [si   (get @sessions sid)
              msg  (:msg  si)]
          (swap! sessions assoc sid
            (assoc si :msg ""))
          msg)))))

(defn session-put
  [sid cmd]
  (let [s (:sess (get @sessions sid))]
    (when s
      (locking s
        (let [si   (get @sessions sid)
              tops (:total-ops si)]
          (swap! sessions assoc sid
            (assoc si
              :last-code     (:code cmd)
              :last-cmd-time (System/currentTimeMillis)
              :total-ops     (inc tops)))))
      (put s cmd))))

(defn session-poll
  [sid]
  (let [s (:sess (get @sessions sid))]
    (when s
      (locking s
        (let [si   (get @sessions sid)
              tops (:total-ops si)]
          (swap! sessions assoc sid
            (assoc si
              :last-req-time (System/currentTimeMillis)
              :total-ops     (inc tops)))))
      (poll s))))

(def ^:const session-age (str (* 3600 24 30)))

(defn get-active-browser-sessions
  []
  (let [s (cookies/get :repl-sess)]
    (if (= s nil)
      {}
      (let [m (json/parse-string s)]
        (if-not (map? m)
          {}
          (loop [r {} m m]
            (if (empty? m)
              r
              (let [[name sid] (first m)]
                (if (active-session? sid)
                  (recur (assoc r name sid) (rest m))
                  (recur r (rest m)))))))))))

(defn get-sess-id
  [sname client-ip]
  (let [as  (get-active-browser-sessions)
        sid (get as sname)]
    (if-not sid
      (let [new-sid (init-session client-ip sname)
            new-as  (assoc as sname new-sid)
            cval (json/generate-string new-as)]
        ;(println "new session map id (valid 30 days):" cval sname)
        (cookies/put! :repl-sess  {:value cval
                                   :path "/ringmon"
                                   :max-age session-age})
        new-sid)
      sid)))

(defn current-sid
 "Assumes only one session per browser. Improve later"
  []
  (second (first (get-active-browser-sessions))))

(defn do-cmd
  [code sname client-ip]
  (let [sid (get-sess-id sname client-ip)]
    (when sid
      (if (not= code "")
        (let [r (session-put sid {:op :eval :code code})]
          ;(println "put response:"r)
          r)
        (let [r (session-poll sid)]
          ;(when-not (empty? r) (println "bkg polled:" r))
          r)))))

(defn get-chat-msg
  [sname client-ip]
  (let [sid (get-sess-id sname client-ip)]
    (when sid
      (let [m (session-fetch-msg sid)]
        m))))

(defn send-chat-msg
  [msg to sname client-ip]
  (when-let [sid (get-sess-id sname client-ip)]
    (if (= to "")
      (send-chat sid msg [])
      (send-chat sid msg [to]))))

(defn set-chat-nick
  [nick sname client-ip]
  (when-let [sid (get-sess-id sname client-ip)]
    (session-set-nick sid nick)))

(defn break
  [sname client-ip]
  (let [sid (get-sess-id sname client-ip)]
    (when sid
      (let [s (:sess (get @sessions sid))]
        (when s
          (let [cid (get-pending s)]
            ;(println "requesting break for" cid)
            (if cid
              (let [r (put s {:op :interrupt :interrupt-id cid})]
                ;(println "break response:" r)
                r)
              (println "break: no command pending"))))))))

