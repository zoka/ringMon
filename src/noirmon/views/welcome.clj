(ns noirmon.views.welcome
  (:require [noirmon.views.common :as common]
            [noir.response :as resp]
            [noir.request :as req]
            [clojure.java.jmx :as jmx])
  (:use [noir.core :only [defpage]]
        [hiccup.core :only [html]]))

;
;  Just for demo,
;  in real world application default route would point to
;  to main application page (such as index.html)
;
  
(defpage "/" []
  (resp/redirect "/admin/monview.html"))


