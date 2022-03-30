(ns skylobby.util
  (:require
    [clojure.edn :as edn]
    [reitit.frontend.easy :as rfe]))


(defn get-server-key [server-url username]
  (str username "@" server-url))

(defn params-server-key [parameters]
  (if-let [s (get-in parameters [:path :server-key])]
    (edn/read-string s)
    (let [
          server-url (-> parameters :path :server-url)
          username (-> parameters :query :username)]
      (get-server-key server-url username))))


(defn href
  "Return relative url for given route. Url can be used in HTML links."
  ([k]
   (href k nil nil))
  ([k params]
   (href k params nil))
  ([k params query]
   (rfe/href k params query)))


(defn format-hours [moment]
  (.format moment "HH:mm:ss"))


(defn spring-color-to-web [spring-color]
  (let [i (cond
            (string? spring-color)
            (js/parseInt spring-color)
            (number? spring-color)
            (int spring-color)
            :else 0)
        ; https://stackoverflow.com/a/11866980
        b (bit-and i 0xFF)
        g (unsigned-bit-shift-right (bit-and i 0xFF00) 8)
        r (unsigned-bit-shift-right (bit-and i 0xFF0000) 16)]
    ; string colors reverse r and b
    (str "#" (.padStart (.toString b 16) 2 "0")
             (.padStart (.toString g 16) 2 "0")
             (.padStart (.toString r 16) 2 "0"))))


(defn battle-channel-name [{:keys [battle-id channel-name]}]
  (or channel-name
      (str "__battle__" battle-id)))
