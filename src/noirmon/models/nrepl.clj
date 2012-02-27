(ns noirmon.models.nrepl
  (:require [clojure.tools.nrepl.server    :as server]
            [clojure.tools.nrepl.misc      :as misc]
            [clojure.tools.nrepl.transport :as t]
            [clojure.tools.nrepl           :as repl]))


(def nserver (atom nil))  
(def nport (atom 0))

(defn init []
  (let [srv     (server/start-server :port 0)
        port    (.getLocalPort (:ss @srv))]
        (reset! nserver srv)
        (reset! nport port)))

(defn get-connection []
  (repl/connect :port @nport))

;; transient session
(defn handle-transient [req]
  (when-not @nport
    (init))
  (with-open [conn (repl/connect :port @nport)]
     (-> (repl/client conn 5000) 
       (repl/message req)
       doall)))




