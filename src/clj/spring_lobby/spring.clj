(ns spring-lobby.spring
  "Interface to run Spring."
  (:require
    [clojure.core.async :as async]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.set]
    [clojure.string :as string]
    clojure.walk
    [com.evocomputing.colors :as colors]
    [me.raynes.fs :as raynes-fs]
    [skylobby.fs :as fs]
    [skylobby.fx.event :as fx.event]
    [skylobby.task :as task]
    [skylobby.util :as u]
    [spring-lobby.client.message :as client]
    [spring-lobby.client.util :as cu]
    [spring-lobby.sound :as sound]
    [taoensso.timbre :as log])
  (:import
    (javafx.animation KeyFrame KeyValue Timeline)
    (javafx.event EventHandler)
    (javafx.scene.media MediaPlayer)
    (javafx.util Duration)))


(set! *warn-on-reflection* true)


(def ^:dynamic spring-type nil)


(def startpostypes
  {0 "Fixed"
   1 "Random"
   2 "Choose in game"
   3 "Choose before game"})

(def map-multiplier
  "Multiplier from positions in map file into positions that Spring uses."
  8.0)

(def startpostypes-by-name
  (clojure.set/map-invert startpostypes))


(defn startpostype-name [startpostype]
  (when startpostype
    (let [startpostype (int
                         (or
                           (if (string? startpostype)
                             (edn/read-string startpostype)
                             startpostype)
                           0))]
      (get startpostypes startpostype))))


(defn unit-rgb
  [i]
  (/ i 255.0))

(defn format-color [team-color]
  (when-let [decimal-color (or (when (number? team-color) team-color)
                               (try (Integer/parseInt team-color)
                                    (catch Exception _ nil)))]
    (let [[r g b _a] (:rgba (colors/create-color decimal-color))]
      (str (unit-rgb b) " " (unit-rgb g) " " (unit-rgb r))))) ; Spring lobby uses bgr

(defn team-name [id]
  (str "team" id))

(defn players-and-bots
  "Returns the non-spectating players and bots in a battle."
  [battle]
  (filter
    (comp :mode :battle-status second)
    (merge (:users battle) (:bots battle))))

(defn teams [battle]
  (map
    (comp first second)
    (group-by (comp :id :battle-status second)
      (players-and-bots battle))))

(defn team-keys [teams]
  (set (map (comp team-name :id :battle-status second) teams)))

(defn normalize-team
  "Returns :team1 from either :team1 or :1."
  [team-kw]
  (let [[_all team] (re-find #"(\d+)" (name team-kw))]
    (keyword (str "team" team))))

(defn team-str
  "Returns \"team1\" from either :team1, :1, \"1\", or \"team1\"."
  [team-kw]
  (let [[_all team] (re-find #"(\d+)" (name team-kw))]
    (str "team" team)))

(defn map-teams [map-details]
  (or (->> map-details :mapinfo :teams
           (map
             (fn [[k v]]
               [(normalize-team k)
                (-> v
                    (update-in [:startpos :x] u/to-number)
                    (update-in [:startpos :z] u/to-number))]))
           seq)
      (->> map-details
           :smd
           :map
           (filter (comp #(string/starts-with? % "team") name first))
           (map
             (fn [[k {:keys [startposx startposz]}]]
               [(normalize-team k)
                {:startpos {:x startposx :z startposz}}]))
           (into {}))
      []))

(defn script-data-client
  [battle {:keys [game]}]
  (let [hostip-override (get-in battle [:scripttags "game" "hostip"])]
    {"game"
     {"hostip" (if (string/blank? hostip-override)
                 (:battle-ip battle)
                 hostip-override)
      "hostport" (:battle-port battle)
      "ishost" 0
      "myplayername" (get game "myplayername")
      "mypasswd" (:script-password battle)}}))

(defn script-data-host
  "Given data for a battle, returns data that can be directly formatted to script.txt format for Spring."
  ([battle {:keys [is-host map-details mod-details sides singleplayer] :as opts}]
   (let [teams (teams battle)
         ally-teams (set
                      (map
                        (comp :ally :battle-status second)
                        (filter
                          (comp :mode :battle-status second)
                          (mapcat battle [:users :bots]))))
         team-keys (team-keys teams)
         existing-team? (fn [[k _v]]
                          (if (string/starts-with? (name k) "team")
                            (contains? team-keys k)
                            true))
         default-map-teams {:game
                            (->> (map-teams map-details)
                                 (map
                                   (fn [[k {:keys [startpos]}]]
                                     [(normalize-team k)
                                      {:startposx (:x startpos)
                                       :startposz (:z startpos)}]))
                                 (filter existing-team?)
                                 (into {}))}
         startpostype (get-in battle [:scripttags "game" "startpostype" str])
         bots-scripttags (get-in battle [:scripttags "game" "bots"])
         team-ids (->> battle
                       players-and-bots
                       (map (comp :id :battle-status second))
                       set)
         shuffled-ids (zipmap
                        team-ids
                        (if (= "1" startpostype)
                          (shuffle team-ids)
                          team-ids))
         actual-team-id (fn [id] (get shuffled-ids id id))]
     (u/deep-merge
       (when (= "3" startpostype)
         default-map-teams)
       (update
         (:scripttags battle)
         "game"
         (fn [game]
           (let [all-modoptions (->> mod-details
                                     :modoptions
                                     (map second)
                                     (remove (comp #{"section"} :type)))]
             (->> (update game "modoptions"
                          (fn [modoptions]
                            (->> all-modoptions
                                 (map
                                   (fn [modoption]
                                     (let [k (some-> modoption :key string/lower-case)
                                           default (:def modoption)
                                           v (get modoptions k default)]
                                       [k
                                        (str
                                          (if (= "bool" (:type modoption))
                                            (u/to-number (u/to-bool v))
                                            v))])))
                                 (into {}))))
                  (filter existing-team?)
                  (into {})))))
       {"game"
        (into
          (merge
            {"ishost" (if is-host 1 0)}
            (when-let [gametype (if-let [modinfo (:modinfo mod-details)]
                                  (str (:name modinfo) " " (:version modinfo))
                                  (:battle-modname battle))]
              {"gametype" gametype})
            (if is-host
              {"hostip" "0.0.0.0"}
              (when-let [hostip (:battle-ip battle)]
                {"hostip" hostip}))
            (if-let [hostport (:battle-port battle)]
              {"hostport" hostport}
              (when singleplayer
                {"hostport" (u/open-port)}))
            (when-let [mapname (:battle-map battle)]
              {"mapname" mapname}))
          (concat
            (map
              (fn [[player {:keys [battle-status user]}]]
                (let [team (-> battle-status :id actual-team-id)
                      spectator (if (:mode battle-status) 0 1)]
                  [(str "player" team)
                   {"name" player
                    "team" team
                    "isfromdemo" 0  ; TODO replays
                    "spectator" spectator
                    "countrycode" (:country user)}]))
              (:users battle))
            (map
              (fn [[_player {:keys [battle-status team-color owner]}]]
                (let [team-id (-> battle-status :id actual-team-id)
                      team-leader (if owner
                                    (-> battle :users (get owner) :battle-status :id actual-team-id)
                                    team-id)
                      side (:side battle-status)]
                    [(team-name team-id)
                     {"teamleader" team-leader
                      "handicap" (:handicap battle-status)
                      "allyteam" (:ally battle-status)
                      "rgbcolor" (format-color team-color)
                      "side" (get sides side side)}]))
              teams)
            (map
              (fn [[bot-name {:keys [ai-name ai-version battle-status owner]}]]
                (let [team (-> battle-status :id actual-team-id)
                      host (-> battle :users (get owner) :battle-status :id actual-team-id)]
                  [(str "ai" team)
                   {"name" bot-name
                    "shortname" ai-name
                    "version" ai-version
                    "host" host
                    "isfromdemo" 0  ; TODO replays
                    "team" team
                    "options" (get-in bots-scripttags [bot-name "options"] {})}]))
              (:bots battle))
            (map
              (fn [ally]
                [(str "allyteam" ally) {"numallies" 0}])
              ally-teams)
            (:game opts)))}))))

(defn script-data
  "Given data for a battle, returns data that can be directly formatted to script.txt format for Spring."
  [battle {:keys [is-host] :as opts}]
  (if is-host
    (script-data-host battle opts)
    (script-data-client battle opts)))

(defn script-txt-inner
  ([kv]
   (script-txt-inner "" kv))
  ([tabs [k v]]
   (str tabs
        (if (map? v)
          (str "[" (name k ) "]\n" tabs "{\n"
               (apply str (map (partial script-txt-inner (str tabs "\t")) (sort-by first v)))
               tabs "}\n")
          (when k
            (str (name k) " = " v ";")))
        "\n")))

; https://springrts.com/wiki/Script.txt
; https://github.com/spring/spring/blob/104.0/doc/StartScriptFormat.txt
; https://github.com/springlobby/springlobby/blob/master/src/spring.cpp#L284-L590
(defn script-txt
  "Given data for a battle, return contents of a script.txt file for Spring."
  ([script-data]
   (apply str (map script-txt-inner (sort-by first (clojure.walk/stringify-keys script-data))))))


(defn battle-details [{:keys [battle battles users]}]
  (let [battle (update battle :users
                       #(into {}
                          (map (fn [[k v]]
                                 [k (assoc v :username k :user (get users k))])
                               %)))]
    (merge (get battles (:battle-id battle)) battle)))

; TODO find a better place for this
(defn mod-details [mods filter-mod-name]
  (some->> mods
           (filter (comp #{filter-mod-name} :mod-name))
           first))

(defn parse-side-key [[k v]]
  (try
    (when-let [[_all n] (->> k name (re-find #"(?:side)?(\d+)"))]
      (Integer/parseInt n))
    (catch Exception e
      (log/warn e "Error parsing side" k v))))

(defn mod-sides [mod-details]
  (->> mod-details
       :sidedata
       clojure.walk/stringify-keys
       (filter parse-side-key)
       (sort-by parse-side-key)
       (map (comp #(get % "name") second))
       (map-indexed vector)
       (into {})))

(defn battle-script-txt [{:keys [username battle-map-details battle-mod-details singleplayer] :as state}]
  (let [battle (battle-details state)
        script (script-data battle
                 {:is-host (= username (:host-username battle))
                  :game {"myplayername" username}
                  :map-details battle-map-details
                  :mod-details battle-mod-details
                  :sides (mod-sides battle-mod-details)
                  :singleplayer singleplayer})]
    (script-txt script)))

(defn hidden-directory? [path-str]
  (or (re-find #"^\." path-str)
      (re-find #"/\." path-str)
      (re-find #"\\\." path-str)))


(def spring-settings-paths
  [["springsettings.cfg"]
   ["uikeys.txt"]
   ["LuaUI" "Config"]])

(defn copy-spring-setting [source-dir dest-dir path]
  (try
    (let [source (apply io/file source-dir path)]
      (if (fs/exists? source)
        (do
          (fs/make-dirs (fs/file dest-dir))
          (fs/copy source (apply io/file dest-dir path))
          :copied)
        :does-not-exist))
    (catch Exception e
      (log/warn e "Error copying spring settings" path "from" source-dir "to" dest-dir)
      :error)
    (catch Throwable t
      (log/warn t "Error copying spring settings" path "from" source-dir "to" dest-dir)
      (throw t))))

(defn delete-spring-setting [dir path]
  (try
    (log/info "Deleting spring setting" path "in" dir)
    (let [f (apply io/file dir path)]
      (if (fs/exists? f)
        (do
          (raynes-fs/delete f)
          :deleted)
        :does-not-exist))
    (catch Exception e
      (log/warn e "Error deleting spring settings" path "from" dir)
      :error)))

(defn copy-spring-settings [source-dir dest-dir]
  (->> spring-settings-paths
       (map (juxt identity (partial copy-spring-setting source-dir dest-dir)))
       doall
       (into {})))

(defn delete-spring-settings [dir]
  (log/info "Deleting spring settings in" dir)
  (->> spring-settings-paths
       (map (juxt identity (partial delete-spring-setting dir)))
       (into {})))

(defn get-envp [] nil)


(defn wait-for-spring [^java.lang.Process process state-atom spring-log-state {:keys [infolog-dest]}]
  (let [exit-code (.waitFor process)]
    (log/info "Spring exited with code" exit-code)
    (when (not= 0 exit-code)
      (log/info "Non-zero spring exit, showing info window")
      (let [spring-log @spring-log-state
            archive-not-found (when-let [[_all archive-name _ resolved] (some #(re-find #"errorMsg=\"Dependent archive \"([^\"]*)\" (\(resolved to \"([^\"]*)\"\))? not found\"" %)
                                                                              (map :line spring-log))]
                                {:archive-name archive-name
                                 :resolved-archive-name resolved})]
        (swap! state-atom assoc
               :show-spring-info-window true
               :spring-log spring-log
               :spring-crash-infolog-file infolog-dest
               :spring-crash-archive-not-found archive-not-found)))))

(def spring-out-filename "skylobby-spring.log")

(defn start-game
  [state-atom
   {:keys [client-data engine-version engines engines-by-version script-txt server-key
           ^java.io.File spring-isolation-dir
           spring-settings username users]
    :as state}]
  (let [my-client-status (-> users (get username) :client-status)
        now (u/curr-millis)
        server-key (or server-key
                       (u/server-key client-data))
        battle-id (-> state :battle :battle-id)
        {:keys [debug-spring engine-overrides ^MediaPlayer media-player music-paused refresh-replays-after-game ring-when-game-ends]}
        (swap! state-atom
          (fn [state]
            (-> state
                (assoc-in [:spring-starting server-key battle-id] false)
                (assoc-in [:spring-running server-key battle-id] true))))
        spring-out-file (fs/file (fs/app-root) spring-out-filename)
        pre-game-fn (fn []
                      (try
                        (if (and media-player (not music-paused))
                          (do
                            (log/info "Pausing media player")
                            (let [^"[Ljavafx.animation.KeyFrame;"
                                  keyframes (into-array KeyFrame
                                              [(KeyFrame.
                                                 (Duration/seconds 3)
                                                 (into-array KeyValue
                                                   [(KeyValue. (.volumeProperty media-player) 0)]))])
                                  timeline (Timeline.  keyframes)]
                              (.setOnFinished timeline
                                (reify EventHandler
                                  (handle [_this _e]
                                    (.pause media-player)
                                    (swap! state-atom assoc :music-paused true))))
                              (.play timeline)))
                          (when (not media-player)
                            (log/info "No media player to pause")))
                        (catch Exception e
                          (log/error e "Error pausing music")))
                      (when (:auto-backup spring-settings)
                        (let [auto-backup-name (str "backup-" (u/format-datetime (u/curr-millis)))
                              dest-dir (fs/file (fs/spring-settings-root) auto-backup-name)]
                          (log/info "Backing up Spring settings to" dest-dir)
                          (let [res (copy-spring-settings spring-isolation-dir dest-dir)]
                            (log/info "Copied Spring settings" res))))
                      (when (:game-specific spring-settings)
                        (log/info "Backing up game specific settings")
                        (if-let [game-type (-> state :battle-mod-details :mod-name-only)]
                          (do
                            (log/info "Game type" game-type)
                            (let [source-dir (fs/file (fs/spring-settings-root) game-type)]
                              (if (fs/exists? source-dir)
                                (do
                                  (delete-spring-settings spring-isolation-dir)
                                  (log/info "Restoring game Spring settings from" source-dir)
                                  (let [res (copy-spring-settings source-dir spring-isolation-dir)]
                                    (log/info "Copied Spring settings" res)))
                                (log/info "Game specific settings do not exist, skipping"))))
                          (log/warn "Unable to determine game type from details with keys" (pr-str (keys (:battle-mod-details state))))))
                      (locking spring-out-filename
                        (spit spring-out-file "")))
        infologs-dir (fs/file spring-isolation-dir "infologs")
        infolog-dest (fs/file infologs-dir (str "infolog_" now ".txt"))
        post-game-fn (fn []
                       (try
                         (let [
                               infolog-src (fs/file spring-isolation-dir "infolog.txt")]
                           (if (fs/exists? infolog-src)
                             (do
                               (fs/make-dirs infologs-dir)
                               (log/info "Copying infolog to" infolog-dest)
                               (fs/copy infolog-src infolog-dest))
                             (log/warn "Infolog file does not exist:" infolog-src)))
                         (catch Exception e
                           (log/error e "Error backing up infolog")))
                       (when (:unready-after-game state)
                         (let [
                               state (swap! state-atom update-in [:by-server server-key]
                                       (fn [server-data]
                                         (if (:battle server-data)
                                           (assoc-in server-data [:battle :desired-ready] false)
                                           server-data)))
                               me (-> state :by-server (get server-key) :battle :users (get username))]
                           (when-let [{:keys [battle-status team-color]} me]
                             (client/send-message state-atom client-data
                               (str "MYBATTLESTATUS " (cu/encode-battle-status (assoc battle-status :ready false)) " " team-color)))))
                       (when (:game-specific spring-settings)
                         (if-let [game-type (-> state :battle-mod-details :mod-name-only)]
                           (do
                             (log/info "Game type" game-type)
                             (let [dest-dir (fs/file (fs/spring-settings-root) game-type)]
                               (log/info "Backing up Spring settings to" dest-dir)
                               (let [res (copy-spring-settings spring-isolation-dir dest-dir)]
                                 (log/info "Copied Spring settings" res))))
                           (log/warn "Unable to determine game type from details with keys" (pr-str (keys (:battle-mod-details state))))))
                       (if (and media-player (not music-paused))
                         (do
                           (log/info "Resuming media player")
                           (.play media-player)
                           (let [{:keys [music-volume]} (swap! state-atom assoc :music-paused false)
                                 ^"[Ljavafx.animation.KeyFrame;"
                                 keyframes (into-array KeyFrame
                                             [(KeyFrame.
                                                (Duration/seconds 3)
                                                (into-array KeyValue
                                                  [(KeyValue. (.volumeProperty media-player) (or (u/to-number music-volume) 1.0))]))])
                                 timeline (Timeline. keyframes)]
                             (.play timeline)))
                         (when (not media-player)
                           (log/info "No media player to resume")))
                       (when ring-when-game-ends
                         (sound/play-ring state))
                       (when refresh-replays-after-game
                         (task/add-task! state-atom {:spring-lobby/task-type :spring-lobby/refresh-replays})))
        set-ingame (fn [ingame]
                     (log/info "Setting ingame" ingame "for" server-key)
                     (if (#{:direct-client :direct-host} (u/server-type server-key))
                       (fx.event/update-user-state state-atom server-key {:username username} {:client-status {:ingame ingame}})
                       (client/send-message state-atom client-data
                         (str "MYSTATUS "
                              (cu/encode-client-status
                                (assoc my-client-status :ingame ingame))))))
        spring-log-state (atom [])]
    (try
      (log/info "Preparing to start game")
      (try
        (pre-game-fn)
        (catch Exception e
          (log/error e "Error running pre-game-fn")))
      (set-ingame true)
      (log/info "Creating game script")
      (let [{:keys [battle-version]} (battle-details state)
            engine-version (or engine-version battle-version)
            script-txt (or script-txt (battle-script-txt state))
            engine-dir (or (get-in engine-overrides [(fs/canonical-path spring-isolation-dir) engine-version])
                           (:file (get engines-by-version engine-version))
                           (some->> engines
                                    (filter (comp #{engine-version} :engine-version))
                                    first
                                    :file))
            engine-file (io/file engine-dir (case spring-type
                                              :dedicated (fs/spring-dedicated-executable)
                                              :headless (fs/spring-headless-executable)
                                              ; else
                                              (fs/spring-executable)))
            _ (log/info "Engine executable" (pr-str engine-file))
            _ (fs/set-executable engine-file)
            script-file (io/file spring-isolation-dir "script.txt")
            script-file-param (fs/wslpath script-file)
            isolation-dir-param (fs/wslpath engine-dir)
            write-dir-param (fs/wslpath spring-isolation-dir)]
        (spit script-file script-txt)
        (log/info "Wrote script to" script-file)
        (let [command [(fs/canonical-path engine-file)
                       "--isolation-dir" isolation-dir-param
                       "--write-dir" write-dir-param
                       script-file-param]]
          (if debug-spring
            (do
              (log/info "Setting spring command for debug mode")
              (swap! state-atom assoc :show-spring-debug true :spring-debug-command command))
            (let [^"[Ljava.lang.String;" cmdarray (into-array String command)
                  ^"[Ljava.lang.String;" envp (get-envp)
                  runtime (Runtime/getRuntime)
                  process (.exec runtime cmdarray envp spring-isolation-dir)
                  pid (.pid process)]
              (log/info "Running '" command "'")
              (async/thread
                (with-open [^java.io.BufferedReader reader (io/reader (.getInputStream process))]
                  (loop []
                    (if-let [line (.readLine reader)]
                      (do
                        (log/info "(spring" pid "out)" line)
                        (swap! spring-log-state conj {:stream :out :line line})
                        (locking spring-out-filename
                          (spit spring-out-file (str line \newline) :append true))
                        (recur))
                      (log/info "Spring stdout stream closed")))))
              (async/thread
                (with-open [^java.io.BufferedReader reader (io/reader (.getErrorStream process))]
                  (loop []
                    (if-let [line (.readLine reader)]
                      (do
                        (log/info "(spring" pid "err)" line)
                        (swap! spring-log-state conj {:stream :err :line line})
                        (locking spring-out-filename
                          (spit spring-out-file (str line \newline) :append true))
                        (recur))
                      (log/info "Spring stderr stream closed")))))
              (try
                (wait-for-spring process state-atom spring-log-state {:infolog-des infolog-dest})
                (try
                  (post-game-fn)
                  (catch Exception e
                    (log/error e "Error in post-game-fn")))
                (catch Exception e
                  (log/error e "Error waiting for Spring to close"))
                (catch Throwable t
                  (log/error t "Fatal error waiting for Spring to close")
                  (throw t)))))))
      (catch Exception e
        (log/error e "Error starting game" {:spring-isolation-dir spring-isolation-dir}))
      (finally
        (swap! state-atom
          (fn [state]
            (-> state
                (assoc-in [:spring-starting server-key battle-id] false)
                (assoc-in [:spring-running server-key battle-id] false))))
        (set-ingame false)))))


(defn watch-replay
  [state-atom {:keys [engine-version engines replay-file ^java.io.File spring-isolation-dir]}]
  (try
    (log/info "Watching replay" replay-file)
    (let [engine-dir (some->> engines
                              (filter (comp #{engine-version} :engine-version))
                              first
                              :file)
          engine-file (io/file engine-dir (fs/spring-executable))
          _ (log/info "Engine executable" engine-file)
          _ (fs/set-executable engine-file)
          replay-file-param (fs/wslpath replay-file)
          isolation-dir-param (fs/wslpath engine-dir)
          write-dir-param (fs/wslpath spring-isolation-dir)
          command [(fs/canonical-path engine-file)
                   "--isolation-dir" isolation-dir-param
                   "--write-dir" write-dir-param
                   replay-file-param]
          runtime (Runtime/getRuntime)
          spring-log-state (atom [])]
      (log/info "Running '" command "'")
      (let [^"[Ljava.lang.String;" cmdarray (into-array String command)
            ^"[Ljava.lang.String;" envp (get-envp)
            process (.exec runtime cmdarray envp spring-isolation-dir)
            pid (.pid process)]
        (async/thread
          (with-open [^java.io.BufferedReader reader (io/reader (.getInputStream process))]
            (loop []
              (if-let [line (.readLine reader)]
                (do
                  (log/info "(spring" pid "out)" line)
                  (swap! spring-log-state conj {:stream :out :line line})
                  (recur))
                (log/info "Spring stdout stream closed")))))
        (async/thread
          (with-open [^java.io.BufferedReader reader (io/reader (.getErrorStream process))]
            (loop []
              (if-let [line (.readLine reader)]
                (do
                  (log/info "(spring" pid "err)" line)
                  (swap! spring-log-state conj {:stream :err :line line})
                  (recur))
                (log/info "Spring stderr stream closed")))))
        (wait-for-spring process state-atom spring-log-state nil)))
    (catch Exception e
      (log/error e "Error starting replay" replay-file))))
