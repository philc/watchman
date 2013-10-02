(ns watchman.utils)

; sget and sget-in (originally safe-get and safe-get-in) were lifted from the old clojure-contrib map-utils:
; https://github.com/richhickey/clojure-contrib/blob/master/src/main/clojure/clojure/contrib/map_utils.clj
(defn sget
  "Like get, but throws an exception if the key is not found."
  [m k]
  (if-let [pair (find m k)]
    (val pair)
    (throw (IllegalArgumentException. (format "Key %s not found in %s" k m)))))

(defn sget-in
  "Like get-in, but throws an exception if any key is not found."
  [m ks]
  (reduce sget m ks))
