;
;  All in one JSON parser using only one dependency:
;  [com.fasterxml.jackson.core/jackson-core "2.0.0"]
;  Providing only  generate-string and parse-string
;  Cherry picked from Cheshire.

(ns ringmon.json
 (:import
   (com.fasterxml.jackson.core
    JsonParser
    JsonFactory
    JsonToken
    JsonGenerator
    JsonParser$Feature
    JsonFactory$Feature
    JsonGenerationException)
   (java.io StringWriter StringReader)
   (java.util Date Map List Set SimpleTimeZone UUID)
   (java.sql Timestamp)
   (java.text SimpleDateFormat)
   (java.math BigInteger)
   (clojure.lang IPersistentCollection Keyword Ratio Symbol)))

; parse

(declare parse*)

(def ^{:doc "Flag to determine whether float values should be returned as
             BigDecimals to retain precision. Defaults to false."
       :dynamic true}
  *use-bigdecimals?* false)

(definline parse-object [^JsonParser jp keywords? bd? array-coerce-fn]
  `(do
     (.nextToken ~jp)
     (loop [mmap# (transient {})]
       (if-not (= (.getCurrentToken ~jp)
                  JsonToken/END_OBJECT)
         (let [key-str# (.getText ~jp)
               _# (.nextToken ~jp)
               key# (if ~keywords?
                      (keyword key-str#)
                      key-str#)
               mmap# (assoc! mmap# key#
                             (parse* ~jp ~keywords? ~bd? ~array-coerce-fn))]
           (.nextToken ~jp)
           (recur mmap#))
         (persistent! mmap#)))))

(definline parse-array [^JsonParser jp keywords? bd? array-coerce-fn]
  `(let [array-field-name# (.getCurrentName ~jp)]
     (.nextToken ~jp)
     (loop [coll# (transient (if ~array-coerce-fn
                               (~array-coerce-fn array-field-name#)
                               []))]
       (if-not (= (.getCurrentToken ~jp)
                  JsonToken/END_ARRAY)
         (let [coll# (conj! coll#(parse* ~jp ~keywords? ~bd? ~array-coerce-fn))]
           (.nextToken ~jp)
           (recur coll#))
         (persistent! coll#)))))

(defn- parse* [^JsonParser jp keywords? bd? array-coerce-fn]
  (condp = (.getCurrentToken jp)
    JsonToken/START_OBJECT (parse-object jp keywords? bd? array-coerce-fn)
    JsonToken/START_ARRAY (parse-array jp keywords? bd? array-coerce-fn)
    JsonToken/VALUE_STRING (.getText jp)
    JsonToken/VALUE_NUMBER_INT (.getNumberValue jp)
    JsonToken/VALUE_NUMBER_FLOAT (if bd?
                                   (.getDecimalValue jp)
                                   (.getNumberValue jp))
    JsonToken/VALUE_TRUE true
    JsonToken/VALUE_FALSE false
    JsonToken/VALUE_NULL nil
    (throw
     (Exception.
      (str "Cannot parse " (pr-str (.getCurrentToken jp)))))))

(defn- parse [^JsonParser jp fst? keywords? eof array-coerce-fn]
  (let [keywords? (boolean keywords?)]
    (.nextToken jp)
    (if (nil? (.getCurrentToken jp))
      eof
      (parse* jp keywords? *use-bigdecimals?* array-coerce-fn))))

;; default date format used to JSON-encode Date objects
(def default-date-format "yyyy-MM-dd'T'HH:mm:ss'Z'")

(defonce default-factory-options
  {:auto-close-source false
   :allow-comments false
   :allow-unquoted-field-names false
   :allow-single-quotes false
   :allow-unquoted-control-chars true
   :allow-backslash-escaping false
   :allow-numeric-leading-zeros false
   :allow-non-numeric-numbers false
   :intern-field-names false
   :canonicalize-field-names false})

;; Factory objects that are needed to do the encoding and decoding
(defn ^JsonFactory make-json-factory
  [opts]
  (let [opts (merge default-factory-options opts)]
    (doto (JsonFactory.)
      (.configure JsonParser$Feature/AUTO_CLOSE_SOURCE
                  (boolean (:auto-close-source opts)))
      (.configure JsonParser$Feature/ALLOW_COMMENTS
                  (boolean (:allow-comments opts)))
      (.configure JsonParser$Feature/ALLOW_UNQUOTED_FIELD_NAMES
                  (boolean (:allow-unquoted-field-names opts)))
      (.configure JsonParser$Feature/ALLOW_SINGLE_QUOTES
                  (boolean (:allow-single-quotes opts)))
      (.configure JsonParser$Feature/ALLOW_UNQUOTED_CONTROL_CHARS
                  (boolean (:allow-unquoted-control-chars opts)))
      (.configure JsonFactory$Feature/INTERN_FIELD_NAMES
                  (boolean (:intern-field-names opts)))
      (.configure JsonFactory$Feature/CANONICALIZE_FIELD_NAMES
                  (boolean (:canonicalize-field-names opts))))))

(defonce ^JsonFactory json-factory (make-json-factory default-factory-options))

; generators

(definline write-string [^JsonGenerator jg ^String str]
  `(.writeString ~jg ~str))

(definline fail [obj ^Exception e]
  `(throw (or ~e (JsonGenerationException.
                  (str "Cannot JSON encode object of class: "
                       (class ~obj) ": " ~obj)))))

(defmacro number-dispatch [^JsonGenerator jg obj ^Exception e]
  (if (< 2 (:minor *clojure-version*))
    `(condp instance? ~obj
       Integer (.writeNumber ~jg (int ~obj))
       Long (.writeNumber ~jg (long ~obj))
       Double (.writeNumber ~jg (double ~obj))
       Float (.writeNumber ~jg (double ~obj))
       BigInteger (.writeNumber ~jg ^BigInteger ~obj)
       BigDecimal (.writeNumber ~jg ^BigDecimal ~obj)
       Ratio (.writeNumber ~jg (double ~obj))
       clojure.lang.BigInt (.writeNumber ~jg ^clojure.lang.BigInt
                                         (.toBigInteger (bigint ~obj)))
       (fail ~obj ~e))
    `(condp instance? ~obj
       Integer (.writeNumber ~jg (int ~obj))
       Long (.writeNumber ~jg (long ~obj))
       Double (.writeNumber ~jg (double ~obj))
       Float (.writeNumber ~jg (float ~obj))
       BigInteger (.writeNumber ~jg ^BigInteger ~obj)
       BigDecimal (.writeNumber ~jg ^BigDecimal ~obj)
       Ratio (.writeNumber ~jg (double ~obj))
       (fail ~obj ~e))))

(declare generate)

(definline generate-map [^JsonGenerator jg obj ^String date-format ^Exception e]
  `(do
     (.writeStartObject ~jg)
     (doseq [[k# v#] ~obj]
       (.writeFieldName ~jg (if (keyword? k#)
                              (.substring (str k#) 1)
                              (str k#)))
       (generate ~jg v# ~date-format ~e))
     (.writeEndObject ~jg)))

(definline generate-array [^JsonGenerator jg obj ^String date-format
                           ^Exception e]
  `(do
     (.writeStartArray ~jg)
     (doseq [item# ~obj]
       (generate ~jg item# ~date-format ~e))
     (.writeEndArray ~jg)))

(defn- generate [^JsonGenerator jg obj ^String date-format ^Exception ex]
  (condp instance? obj
    IPersistentCollection (condp instance? obj
                            clojure.lang.IPersistentMap
                            (generate-map jg obj date-format ex)
                            clojure.lang.IPersistentVector
                            (generate-array jg obj date-format ex)
                            clojure.lang.IPersistentSet
                            (generate jg (seq obj) date-format ex)
                            clojure.lang.IPersistentList
                            (generate-array jg obj date-format ex)
                            clojure.lang.ISeq
                            (generate-array jg obj date-format ex))
    Map (generate-map jg obj date-format ex)
    List (generate-array jg obj date-format ex)
    Set (generate jg (seq obj) date-format ex)
    Number (number-dispatch ^JsonGenerator jg obj ex)
    String (write-string ^JsonGenerator jg ^String obj )
    Keyword (write-string ^JsonGenerator jg
                          (if-let [ns (namespace obj)]
                            (str ns "/" (name obj))
                            (name obj)))
    UUID (write-string ^JsonGenerator jg (.toString ^UUID obj))
    Symbol (write-string ^JsonGenerator jg (.toString ^Symbol obj))
    Boolean (.writeBoolean ^JsonGenerator jg ^Boolean obj)
    Date (let [sdf (doto (SimpleDateFormat. date-format)
                     (.setTimeZone (SimpleTimeZone. 0 "UTC")))]
           (write-string ^JsonGenerator jg (.format sdf obj)))
    Timestamp (let [date (Date. (.getTime ^Timestamp obj))
                    sdf (doto (SimpleDateFormat. date-format)
                          (.setTimeZone (SimpleTimeZone. 0 "UTC")))]
                (write-string ^JsonGenerator jg (.format sdf obj)))
    (if (nil? obj)
      (.writeNull ^JsonGenerator jg)
      ;; it must be a primative then
      (try
        (.writeNumber ^JsonGenerator jg obj)
        (catch Exception e (fail obj ex))))))

;-------------- Public functions

(defn ^String generate-string
  "Returns a JSON-encoding String for the given Clojure object. Takes an
  optional date format string that Date objects will be encoded with.

  The default date format (in UTC) is: yyyy-MM-dd'T'HH:mm:ss'Z'"
  [obj & [^String date-format ^Exception e]]
  (let [sw (StringWriter.)
        generator (.createJsonGenerator ^JsonFactory json-factory sw)]
    (generate generator obj (or date-format default-date-format) e)
    (.flush generator)
    (.toString sw)))

(defn parse-string
 "Returns the Clojure object corresponding to the given JSON-encoded string.
  keywords? should be true if keyword keys are needed, the default is false
  maps will use strings as keywords.

  The array-coerce-fn is an optional function taking the name of an array field,
  and returning the collection to be used for array values."
  [^String string & [^Boolean keywords? array-coerce-fn]]
  (when string
    (parse
     (.createJsonParser ^JsonFactory json-factory
                        (StringReader. string))
     true (or keywords? false) nil array-coerce-fn)))

