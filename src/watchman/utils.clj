(ns watchman.utils
  (:require [clj-time.core :as time-core]
            [clj-time.format :as time-format]
            [clojure.java.io :refer [reader writer]]))

; dev-logging controls whether we log basic request info and exceptions to stdout, for dev workflows.
(def ^:private dev-logging (and (not= (System/getenv "RING_ENV") "production")))

(defn get-env-var
  "Returns the env var with the given name, throwing an exception if it's blank in production."
  ([variable-name]
     (get-env-var variable-name true))
  ([variable-name fail-if-blank]
     (let [value (System/getenv variable-name)]
       (when (and (empty? value) (= (System/getenv "RING_ENV") "production"))
         (throw (Exception. (format "Env variable %s is blank." variable-name))))
       value)))

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

; Taken from http://clojuredocs.org/clojure_contrib/clojure.contrib.seq/indexed.
(defn indexed
  "Returns a lazy sequence of [index, item] pairs, where items come from coll and indexes count up from zero.
  (indexed '(a b c d))  =>  ([0 a] [1 b] [2 c] [3 d])
  (indexed '(a b c d) '(x y))  =>  ([0 a x] [1 b y])"
  [& colls]
  (apply map vector (iterate inc 0) colls))


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
  "Log the given exception to the exceptions log file in an unbuffered manner. Returns nil."
  [preface exception]
  (let [line (format "%s %s:\n%s" (timestamp-now-millis) preface (str exception))]
    (when dev-logging (println line))
    (log-to-dated-file "log/exceptions" line)))

(defn log-info
  "Log the given string to the info log file in an unbuffered manner. Returns nil."
  [message]
  (let [line (format "%s %s" (timestamp-now-millis) message)]
    (when dev-logging (println line))
    (log-to-dated-file "log/info" line)))
