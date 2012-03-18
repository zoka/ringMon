(ns ringmon.api
  (:require
    [ringmon.nrepl    :as repl]
    [ringmon.monitor  :as mon]))

"User nREPL scripts should call only ringMon functions from this namespace
 to insure backwards compatibility"

(defn set-nick
 "Change your chat nick. Return the old one if succesful, nil otherwise."
  [nick]
  (let [sid (repl/current-sid)]
    (repl/session-set-nick sid nick)))

(defn get-nick
 "Get your session chat nick"
  []
  (let [sid (repl/current-sid)]
    (repl/session-get-nick sid)))

(defn chat-nicks
 "Get vector of all active nicks."
  []
  (repl/chat-nicks))

(defn chat-send
 "Send chat message to everybody or to a list of nicks.
  For example:
  (chat-send \"Hello all \") ; send to all
  ; send only to Alice and Bob
  (chat-send \"Hello Alice and Bob \" \"Alice\" \"Bob\""
  [msg & nicks]
  (let [sid   (repl/current-sid)
        nicks (into [] nicks)]
    (repl/chat-send sid msg nicks)))
