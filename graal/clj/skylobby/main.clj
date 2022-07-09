(ns skylobby.main
  (:require
    [clojure.string :as string]
    [clojure.tools.cli :as cli]
    skylobby.core
    [skylobby.cli :as cli-demo]
    [skylobby.cli.util :as cu]
    [skylobby.direct :as direct]
    [skylobby.fs :as fs]
    [skylobby.spring :as spring]
    [skylobby.tui :as tui]
    [skylobby.util :as u]
    [taoensso.timbre :as log])
  (:import
    (java.awt Desktop Desktop$Action))
  (:gen-class))


(set! *warn-on-reflection* true)


(def cli-options
  [
   ["-h" "--help" "Print help and exit"]
   [nil "--version" "Print version and exit"]
   [nil "--quiet" "Log only to SKYLOBBY_ROOT/skylobby.log and not stdout"]
   [nil "--port PORT" "Port to use for web ui AND ipc"]
   [nil "--no-web-ui" "Disables web UI"]
   [nil "--skylobby-root SKYLOBBY_ROOT" "Set the config and log dir for skylobby"]
   [nil "--spring-root SPRING_ROOT" "Set the spring-root config to the given directory"]
   [nil "--spring-type SPRING_TYPE" "Set the spring engine executable type to use, \"dedicated\" or \"headless\"."
    :parse-fn keyword]])


(defn usage [options-summary]
  (->> [""
        u/app-name
        ""
        (str "Usage: " u/app-name " [options] action")
        ""
        "Options:"
        options-summary
        ""
        "Actions:"
        "  cli       Demo CLI interface"
        "  direct    Direct connect server"
        "  tui       Text user interface mode"
        "  <none>    Start client service with web UI"
        ""]
       (string/join \newline)))


(defn browse-url [url]
  (try
    (if-let [^java.awt.Desktop
             desktop (when (Desktop/isDesktopSupported)
                       (let [^java.awt.Desktop
                             desktop (Desktop/getDesktop)]
                         (when (.isSupported desktop Desktop$Action/BROWSE)
                           desktop)))]
      (.browse desktop (java.net.URI. url))
      (let [runtime (Runtime/getRuntime)
            ; https://stackoverflow.com/a/5116553
            command (if (fs/windows?)
                      ["explorer.exe" url]
                      ["xdg-open" url])
            ^"[Ljava.lang.String;" cmdarray (into-array String command)]
        (.exec runtime cmdarray nil nil)))
    (catch Exception e
      (log/error e "Error browsing url" url))))

(defn -main [& args]
  (let [{:keys [arguments errors options summary]} (cli/parse-opts args cli-options :in-order true)
        command (first arguments)
        version (u/version)
        log-path (fs/canonical-path (fs/config-file (str u/app-name ".log")))]
    (if (:quiet options)
      (u/log-only-to-file log-path)
      (u/log-to-file log-path))
    (alter-var-root #'skylobby.util/app-version (fn [& _] version))
    (cond
      errors
      (apply cu/print-and-exit -1
        "Error parsing arguments:\n"
        errors)
      (or (= "help" command)
          (:help options))
      (cu/print-and-exit 0 (usage summary))
      (or (= "version" command)
          (:version options))
      (cu/print-and-exit 0 (str u/app-name " " version))
      (= "cli" command)
      (apply cli-demo/-main (rest arguments))
      (= "direct" (first arguments))
      (apply direct/-main (rest arguments))
      (= "tui" (first arguments))
      (apply tui/-main (rest arguments))
      (seq arguments)
      (cu/print-and-exit -1 "Unknown action: " (pr-str arguments))
      :else
      (do
        (when-let [app-root-override (:skylobby-root options)]
          (alter-var-root #'fs/app-root-override (constantly app-root-override)))
        (when-let [spring-type (:spring-type options)]
          (alter-var-root #'spring/spring-type (constantly spring-type)))
        (let [port (or (:port options)
                       u/default-ipc-port)
              before-state (u/curr-millis)
              _ (log/info "Loading initial state")
              initial-state (skylobby.core/initial-state)
              state (merge
                      initial-state
                      (when (contains? options :spring-root)
                        (let [f (fs/file (:spring-root options))]
                          {
                           :ipc-server-port port
                           :spring-isolation-dir f
                           ::spring-root-arg f})))]
          (log/info "Loaded initial state in" (- (u/curr-millis) before-state) "ms")
          (reset! skylobby.core/*state state)
          (skylobby.core/init skylobby.core/*state)
          (when-not (:no-web-ui options)
            (browse-url (str "http://localhost:" port)))
          (println (pr-str {:port port})))))))
