(ns watchman.utils
  (:require [clj-time.core :as time-core]
   [clj-time.format :as time-format]
   [clojure.java.io :refer [reader writer]]))

; dev-logging controls whether we log basic request info and exceptions to stdout, for dev workflows.
(def ^:private dev-logging (and (not= (System/getenv "RING_ENV") "production")
                                (not= (System/getenv "CLOJURE_ENV") "production")))

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

(defn- timestamp-now-millis
  "Return a string containing the timestamp of the current time, including
  milliseconds (e.g. '2013-08-14T13:54:46.960Z')."
  []
  (time-format/unparse (:date-time time-format/formatters) (time-core/now)))

(defn- dated-log-filename
  "Returns the filename, with today's date appended to it. E.g.
    (dated-log-filename 'info') => info-20131008.log"
  ([filename]
   (dated-log-filename filename (time-core/now)))
  ([filename date]
   (let [date-string (time-format/unparse (:basic-date time-format/formatters) date)]
     (str filename "-" date-string ".log"))))

(defn- log-to-dated-file
  "Returns nil."
  [filename line]
  (let [filename (dated-log-filename filename)]
      (with-open [file (writer filename :append true)]
        (.write file (str line "\n")))
      nil))

(defn log-exception
  "Log the supplied formatted string to the exceptions log file in an unbuffered manner. Returns nil."
  [preface exception]
  (let [line (format "%s %s:\n%s" (timestamp-now-millis) preface (str exception))]
    (when dev-logging (println line))
    (log-to-dated-file "log/exceptions" line)))
