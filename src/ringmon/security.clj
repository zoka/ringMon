(ns ringmon.security)

(defonce black-set (ref #{}))
(defonce white-set (ref #{}))

(def ^:const localhost  "0:0:0:0:0:0:0:1%0") ; ipv6 equivalent of 127.0.0.1

(def sec-sets
  {:white  white-set
   :black  black-set})

(defn add-to-set
 "Add element to the disgnated set."
  [set-key elem]
  (when-let [set (get sec-sets set-key)]
    (dosync
      (alter set conj elem))))

(defn remove-from-set
 "Remove element from the designated set."
  [set-key elem]
  (when-let [set (get sec-sets set-key)]
    (dosync
      (alter set disj elem))))

(defn erase-set
 "Erase designated set."
  [set-key]
  (when-let [set (get sec-sets set-key)]
    (dosync
      (ref-set set #{}))))

(defn check-ip
 "Check the supplied 'ip' address against both white and black list, 
  ie.set, which is mathematically more accurate term.
  Return true if 'ip' is allowed."
  [ip]
  (if (empty? @white-set)
    (when-not (contains? @black-set ip) 
      true)
    (when (contains? @white-set ip)
      true)))
