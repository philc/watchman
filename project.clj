(defproject watchman "0.1.0-SNAPSHOT"
  :description "Keeping watch during the night."
  :url "http://github.com/philc/watchman"
  :main watchman.core
  ; This awt.headless=true prevents some java lib (possibly JNA) from popping up a window upon startup.
  ; http://stackoverflow.com/questions/11740012/clojure-java-pop-up-window
  :jvm-opts ["-Xmx1g" "-Xms1g" "-server" "-Djava.awt.headless=true"]
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [enlive "1.1.4"] ; HTML transformation
                 [com.draines/postal "1.11.0"] ; For sending emails.
                 [clj-http "0.7.0"]
                 [com.cemerick/friend "0.2.0"] ; For authentication.
                 [compojure "1.1.5"]
                 [ring/ring-jetty-adapter "1.2.0"]
                 ; Ring depends on clj-stacktrace, but 0.2.5 includes a critical bugfix.
                 ; https://github.com/mmcgrana/clj-stacktrace/issues/14
                 [clj-stacktrace "0.2.5"]
                 [watchtower "0.1.1"]
                 ; Korma requires a recent jdbc, but lein nondeterministically pulls in an old version.
                 [org.clojure/java.jdbc "0.2.2"]
                 [org.clojars.harob/korma.incubator "0.1.1-SNAPSHOT"] ; Korma, our SQL ORM.
                 [postgresql "9.1-901.jdbc4"] ; Postgres driver
                 ; log4j is included to squelch the verbose initialization output from Korma.
                 [lobos "1.0.0-beta1"] ; Migrations
                 [log4j "1.2.15" :exclusions [javax.mail/mail
                                              javax.jms/jms
                                              com.sun.jdmk/jmxtools
                                              com.sun.jmx/jmxri]]
                 [cheshire "4.0.1"] ; JSON.
                 [org.clojure/core.incubator "0.1.2"] ; for the -?> operator.
                 [overtone/at-at "1.2.0"] ; For scheduling recurring tasks.
                 [clj-time "0.4.4"]
                 [slingshot "0.10.3"]
                 [ring-mock "0.1.3"]]
  :plugins [[lein-ring "0.8.2"]
            [lein-lobos "1.0.0-beta1"]]
  :ring {:handler watchman.handler/app
         :init watchman.handler/init
         :port 8130}
  :profiles
  {:dev {:dependencies [[midje "1.4.0"]
                        [midje-html-checkers "1.0.1"] ; This seems to not work with midje 1.5
                        ; Used by lein-midje; see https://github.com/marick/lein-midje/issues/35
                        [bultitude "0.1.7"]]
         :plugins [[lein-midje "2.0.4"]]}
   :release {:aot :all}})
