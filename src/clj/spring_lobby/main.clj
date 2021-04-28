(ns spring-lobby.main
  (:require
    [cljfx.api :as fx]
    clojure.core.async
    [clojure.tools.cli :as cli]
    [skylobby.fx.root :as fx.root]
    spring-lobby
    [spring-lobby.fs :as fs]
    [spring-lobby.util :as u]
    [taoensso.timbre :as log])
  (:import
    (javafx.application Platform))
  (:gen-class))


(def cli-options
  [[nil "--skylobby-root SKYLOBBY_ROOT" "Set the config and log dir for skylobby"]
   [nil "--spring-root SPRING_ROOT" "Set the spring-root config to the given directory"]
   [nil "--server-url SERVER_URL" "Set the selected server config by url"]])


(defn -main [& args]
  (let [{:keys [options]} (cli/parse-opts args cli-options)]
    (try
      (when-let [app-root-override (:skylobby-root options)]
        (alter-var-root #'fs/app-root-override (constantly app-root-override)))
      (u/log-to-file (fs/canonical-path (fs/config-file (str u/app-name ".log"))))
      (let [before (u/curr-millis)]
        (log/info "Main start")
        (Platform/setImplicitExit true)
        (log/info "Set JavaFX implicit exit")
        (future
          (log/info "Start 7Zip init, async")
          (fs/init-7z!)
          (log/info "Finished 7Zip init"))
        (let [before-state (u/curr-millis)
              _ (log/info "Loading initial state")
              initial-state (spring-lobby/initial-state)
              state (merge
                      initial-state
                      {:standalone true}
                      (when (contains? options :spring-root)
                        {:spring-isolation-dir (fs/file (:spring-root options))})
                      (when (contains? options :server-url)
                        (let [server (->> initial-state
                                          :servers
                                          (filter (comp #{(:server-url options)} first))
                                          first)
                              {:keys [password username]} (->> initial-state
                                                               :logins
                                                               (filter (comp #{(:server-url options)} first))
                                                               first
                                                               second)]
                          {:server server
                           :password password
                           :username username})))]
          (log/info "Loaded initial state in" (- (u/curr-millis) before-state) "ms")
          (reset! spring-lobby/*state state))
        (log/info "Creating renderer")
        (let [r (fx/create-renderer
                  :middleware (fx/wrap-map-desc
                                (fn [state]
                                  {:fx/type fx.root/root-view
                                   :state state}))
                  :opts {:fx.opt/map-event-handler spring-lobby/event-handler})]
          (log/info "Mounting renderer")
          (fx/mount-renderer spring-lobby/*state r))
        (spring-lobby/init-async spring-lobby/*state)
        (log/info "Main finished in" (- (u/curr-millis) before) "ms"))
      (catch Throwable t
        (spit "skylobby-fatal-error.txt" (str t))))))
