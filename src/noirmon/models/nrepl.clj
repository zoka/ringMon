(ns noirmon.models.nrepl
  (:require [clojure.tools.nrepl.server    :as server]
            [clojure.tools.nrepl.misc      :as misc]
            [clojure.tools.nrepl.transport :as t]
            [clojure.tools.nrepl           :as repl])
  (:import  (clojure.tools.nrepl.transport FnTransport)
             (java.util.concurrent 
                LinkedBlockingQueue
                TimeUnit)))

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
               (println "client got data:" data)
               (when-not @open 
                 (.clear server-q)) ; clear q if it was closed while 
               data)))              ; we were blocked in poll
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
               (println "server got data:" data)
               (when-not @open 
                 (.clear client-q))
               data)))
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
               (println "In future")
              (handle handler transport)))
    client-t))


;; transient session
(defn handle-transient [req]
  (println "orig request:" req)
  (with-open [conn (connect)]
    (println "conn" conn) 
    (-> (repl/client conn 100) 
      (repl/message req)
      doall)))




