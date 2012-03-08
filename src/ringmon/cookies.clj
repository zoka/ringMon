;
; Stolen from Noir
;

(ns ringmon.cookies
  "Stateful access to cookie values"
  (:refer-clojure :exclude [get remove])
  (:use ring.middleware.cookies))

(declare ^:dynamic *cur-cookies*)
(declare ^:dynamic *new-cookies*)

(defn put! 
  "Add a new cookie whose name is k and has the value v. If v is a string
  a cookie map is created with :path '/'. To set custom attributes, such as
  \"expires\", provide a map as v. Stores all keys as strings."
  [k v]
  (let [props (if (map? v)
                v
                {:value v :path "/"})]
    (swap! *new-cookies* assoc (name k) props)))

(defn get
  "Get the value of a cookie from the request. k can either be a string or keyword.
   If this is a signed cookie, use get-signed, otherwise the signature will not be
   checked."
  ([k] (get k nil))
  ([k default] 
   (let [str-k (name k)]
     (if-let [v (or (get-in @*new-cookies* [str-k :value]) 
                    (get-in *cur-cookies* [str-k :value]))]
       v
       default))))

(defn noir-cookies [handler]
  (fn [request]
    (binding [*cur-cookies* (:cookies request)
              *new-cookies* (atom {})]
      (let [final (handler request)]
        (assoc final :cookies (merge (:cookies final) @*new-cookies*))))))

(defn wrap-noir-cookies [handler]
  (-> handler
    (noir-cookies)
    (wrap-cookies)))
