(ns ringmon.api
  (:require
    [ringmon.nrepl    :as repl]
    [ringmon.monitor  :as mon]))

"User nREPL scripts should call only ringMon functions from this namespace
 to insure backwards compatibility"

(defn set-nick
 "Change your IRC nick. Return the old one if succesful, nil otherwise."
  [nick]
  (let [sid (repl/current-sid)]
    (repl/session-set-nick sid nick)))

(defn get-nick
 "Get your session IRC nick"
  []
  (let [sid (repl/current-sid)]
    (repl/session-get-nick sid)))

(defn irc-nicks
 "Get vector of all active nicks."
  []
  (repl/irc-nicks))

(defn irc-send
 "Send IRC message to all or a group of IRC nicks."
  [msg & nicks]
  (let [sid   (repl/current-sid)
        nicks (into [] nicks)]
    (repl/irc-send sid msg nicks)))
