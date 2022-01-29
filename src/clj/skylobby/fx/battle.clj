(ns skylobby.fx.battle
  (:require
    [cljfx.api :as fx]
    [cljfx.ext.node :as fx.ext.node]
    [cljfx.lifecycle :as lifecycle]
    [cljfx.mutator :as mutator]
    [cljfx.prop :as prop]
    [clojure.java.io :as io]
    [clojure.string :as string]
    java-time
    [skylobby.discord :as discord]
    [skylobby.fs :as fs]
    [skylobby.fx :refer [monospace-font-family]]
    [skylobby.fx.channel :as fx.channel]
    [skylobby.fx.engine-sync :refer [engine-sync-pane]]
    [skylobby.fx.engines :refer [engines-view]]
    [skylobby.fx.ext :refer [ext-recreate-on-key-changed]]
    [skylobby.fx.font-icon :as font-icon]
    [skylobby.fx.map-sync :refer [map-sync-pane]]
    [skylobby.fx.maps :refer [maps-view]]
    [skylobby.fx.minimap :as fx.minimap]
    [skylobby.fx.mod-sync :refer [mod-sync-pane]]
    [skylobby.fx.mods :refer [mods-view]]
    [skylobby.fx.players-table :refer [players-table]]
    [skylobby.fx.spring-options :as fx.spring-options]
    [skylobby.fx.sub :as sub]
    [skylobby.fx.sync :refer [ok-severity warn-severity error-severity]]
    [skylobby.fx.tooltip-nofocus :as tooltip-nofocus]
    [skylobby.resource :as resource]
    [skylobby.util :as u]
    [spring-lobby.spring :as spring]
    [taoensso.tufte :as tufte])
  (:import
    (javafx.stage Popup)
    (javafx.scene Node)))


(set! *warn-on-reflection* true)


(def minimap-types
  ["minimap" "metalmap" "heightmap"])

(def battle-layouts
  [
   "vertical"
   "horizontal"])

(def font-icon-size 20)


(defn sync-button [{:fx/keys [context]
                    :keys [server-key]}]
  (let [
        my-battle-status (fx/sub-ctx context sub/my-battle-status server-key)
        my-sync-status (fx/sub-ctx context sub/my-sync-status server-key)
        sync-button-style (dissoc
                            (case my-sync-status
                              1 ok-severity
                              2 error-severity
                              ; else
                              warn-severity)
                            :-fx-background-color)
        spring-root (fx/sub-ctx context sub/spring-root server-key)
        battle-id (fx/sub-val context get-in [:by-server server-key :battle :battle-id])
        mod-name (fx/sub-val context get-in [:by-server server-key :battles battle-id :battle-modname])
        map-name (fx/sub-val context get-in [:by-server server-key :battles battle-id :battle-map])
        indexed-map (fx/sub-ctx context sub/indexed-map spring-root map-name)
        indexed-mod (fx/sub-ctx context sub/indexed-mod spring-root mod-name)]
    {:fx/type :button
     :text (str
             " "
             (case my-sync-status
               1 "synced"
               2 "unsynced"
               ; else
               "syncing")
             " ")
     :on-action {:event/type :spring-lobby/clear-map-and-mod-details
                 :map-resource indexed-map
                 :mod-resource indexed-mod
                 :spring-root spring-root}
     :style sync-button-style}))


(defn spring-debug-window
  [{:fx/keys [context]
    :keys [screen-bounds]}]
  (let [
        show (boolean (fx/sub-val context :show-spring-debug))]
    {:fx/type :stage
     :showing (boolean show)
     :title (str u/app-name " Spring Debug")
     :icons skylobby.fx/icons
     :modality :application-modal
     :on-close-request {:event/type :spring-lobby/dissoc
                        :key :show-spring-debug}
     :width (skylobby.fx/fitwidth screen-bounds nil 800)
     :height (skylobby.fx/fitheight screen-bounds nil 200)
     :scene
     {:fx/type :scene
      :stylesheets (fx/sub-ctx context skylobby.fx/stylesheet-urls-sub)
      :root
      {:fx/type :v-box
       :style {:-fx-font-size 16}
       :children
       [{:fx/type :label
         :text "Spring command array"}
        {:fx/type :text-area
         :editable false
         :text (pr-str (fx/sub-val context :spring-debug-command))}
        {:fx/type :label
         :text "Spring command"}
        {:fx/type :text-area
         :editable false
         :text (str (string/join " " (fx/sub-val context :spring-debug-command)))}]}}}))


; https://github.com/cljfx/cljfx/blob/ec3c34e619b2408026b9f2e2ff8665bebf70bf56/examples/e35_popup.clj
(def popup-width 300)
(def ext-with-shown-on
  (fx/make-ext-with-props
    {:shown-on (prop/make
                 (mutator/adder-remover
                   (fn [^Popup popup ^Node node]
                     (let [bounds (.getBoundsInLocal node)
                           node-pos (.localToScreen node (* 0.5 (.getWidth bounds)) 0.0)]
                       (.show popup node
                              (- (.getX node-pos) (* 0.5 popup-width))
                              (.getY node-pos))))
                   (fn [^Popup popup _]
                     (.hide popup)))
                 lifecycle/dynamic)}))

(defn battle-queue-button
  [{:fx/keys [context]
    :keys [server-key]}]
  (let [
        channel-name (fx/sub-ctx context skylobby.fx/battle-channel-sub server-key)
        client-data (fx/sub-val context get-in [:by-server server-key :client-data])
        messages (fx/sub-val context get-in [:by-server server-key :channels channel-name :messages])
        am-spec (fx/sub-ctx context sub/am-spec server-key)
        am-in-queue (->> messages
                         (filter (comp #{"Coordinator"} :username))
                         (filter :text)
                         reverse
                         (reduce
                           (fn [am-in-queue {:keys [text]}]
                             (cond
                               (string/starts-with? text "You are now in the join-queue")
                               true
                               (string/starts-with? text "You have been removed from the join queue")
                               false
                               :else
                               am-in-queue))
                           false))]
    (if am-in-queue
      {:fx/type :button
       :text "Leave Queue"
       :disable (not am-spec)
       :on-action {:event/type :spring-lobby/send-message
                   :channel-name channel-name
                   :client-data client-data
                   :message "$%leaveq"
                   :server-key server-key}}
      {:fx/type :button
       :text "Join Queue"
       :disable (not am-spec)
       :on-action {:event/type :spring-lobby/send-message
                   :channel-name channel-name
                   :client-data client-data
                   :message "$%joinq"
                   :server-key server-key}})))

(defn battle-accolades
  [{:fx/keys [context]
    :keys [server-key]}]
  (let [
        accolades-bot-name "AccoladesBot"
        channel-name (u/user-channel-name accolades-bot-name)
        client-data (fx/sub-val context get-in [:by-server server-key :client-data])
        messages (fx/sub-val context get-in [:by-server server-key :channels channel-name :messages])
        accolade-for (->> messages
                          (filter (comp #{accolades-bot-name} :username))
                          (filter :text)
                          reverse
                          (reduce
                            (fn [accolade-for {:keys [text]}]
                              (let [[_all username] (re-find #"You have an opportunity to leave feedback on one of the players in your last game. We have selected (.*)" text)]
                                (cond
                                  username username
                                  (string/starts-with? text "Thank you for your feedback, this Accolade will be bestowed.")
                                  nil
                                  :else
                                  accolade-for)))
                            nil))
        show-accolades (fx/sub-val context :show-accolades)]
    (if (and show-accolades accolade-for)
      {:fx/type :h-box
       :alignment :center-left
       :children
       (concat
         [{:fx/type :label
           :text (str " Accolade for" accolade-for ": ")}]
         (mapv
           (fn [{:keys [text desc n]}]
             {:fx/type :button
              :text (str text)
              :tooltip {:fx/type :tooltip
                        :text (str desc)}
              :on-action {:event/type :spring-lobby/send-message
                          :channel-name channel-name
                          :client-data client-data
                          :message (str n)
                          :server-key server-key}})
           ; TODO parse from messages
           [
            {:text "None"
             :desc "Regardless of skill they were there when you needed them and tried to help the team."
             :n 0}
            {:text "MVP"
             :desc "Quite clearly the most valuable player on the their team."
             :n 1}
            {:text "MVP"
             :desc "Quite clearly the most valuable player on the their team."
             :n 2}
            {:text "Sportsmanship"
             :desc "Humble in defeat and magnanimous in victory."
             :n 3}
            {:text "Strategist"
             :desc "Knew what to build when, always two steps ahead of their opponents."
             :n 4}
            {:text "Teacher"
             :desc "Made others feel welcome and helped them improve at the game."
             :n 5}]))}
      {:fx/type :pane})))


(defn battle-buttons
  [{:fx/keys [context]
    :keys [server-key my-player team-counts team-skills]}]
  (let [
        singleplayer (= :local server-key)
        bot-name (fx/sub-val context :bot-name)
        bot-username (fx/sub-val context :bot-username)
        bot-username (if (string/blank? bot-username)
                       "bot1"
                       bot-username)
        bot-version (fx/sub-val context :bot-version)
        battle-resource-details (fx/sub-val context :battle-resource-details)
        auto-unspec (fx/sub-val context get-in [:by-server server-key :auto-unspec])
        auto-launch-settings (fx/sub-val context :auto-launch)
        auto-launch (if (contains? auto-launch-settings server-key)
                      (get auto-launch-settings server-key)
                      true)
        battle (fx/sub-val context get-in [:by-server server-key :battle])
        channel-name (fx/sub-ctx context skylobby.fx/battle-channel-sub server-key)
        client-data (fx/sub-val context get-in [:by-server server-key :client-data])
        battles (fx/sub-val context get-in [:by-server server-key :battles])
        users (fx/sub-val context get-in [:by-server server-key :users])
        filter-host-replay (fx/sub-val context :filter-host-replay)
        interleave-ally-player-ids (fx/sub-val context :interleave-ally-player-ids)
        parsed-replays-by-path (fx/sub-val context :parsed-replays-by-path)
        ready-on-unspec (fx/sub-val context :ready-on-unspec)
        show-team-skills (fx/sub-val context :show-team-skills)
        spring-root (fx/sub-ctx context sub/spring-root server-key)
        battle-id (fx/sub-val context get-in [:by-server server-key :battle :battle-id])
        spring-starting (fx/sub-val context get-in [:spring-starting server-key battle-id])
        spring-running (fx/sub-val context get-in [:spring-running server-key battle-id])
        scripttags (fx/sub-val context get-in [:by-server server-key :battle :scripttags])
        engine-version (fx/sub-val context get-in [:by-server server-key :battles battle-id :battle-version])
        engine-details (fx/sub-ctx context sub/indexed-engine spring-root engine-version)
        mod-name (fx/sub-val context get-in [:by-server server-key :battles battle-id :battle-modname])
        map-name (fx/sub-val context get-in [:by-server server-key :battles battle-id :battle-map])
        indexed-map (fx/sub-ctx context sub/indexed-map spring-root map-name)
        indexed-mod (fx/sub-ctx context sub/indexed-mod spring-root mod-name)
        engine-bots (:engine-bots engine-details)
        battle-map-details (fx/sub-ctx context skylobby.fx/map-details-sub indexed-map)
        battle-mod-details (fx/sub-ctx context skylobby.fx/mod-details-sub indexed-mod)
        bots (concat engine-bots
                     (->> battle-mod-details :luaai
                          (map second)
                          (map (fn [ai]
                                 {:bot-name (:name ai)
                                  :bot-version "<game>"}))))
        bot-names (map :bot-name bots)
        bot-name (some #{bot-name} bot-names)
        bot-versions (map :bot-version
                          (get (group-by :bot-name bots)
                               bot-name))
        bot-version (some #{bot-version} bot-versions)
        sides (spring/mod-sides battle-mod-details)
        am-host (fx/sub-ctx context sub/am-host server-key)
        am-spec (fx/sub-ctx context sub/am-spec server-key)
        host-ingame (fx/sub-ctx context sub/host-ingame server-key)
        username (fx/sub-val context get-in [:by-server server-key :username])
        my-client-status (fx/sub-ctx context sub/my-client-status server-key)
        am-away (:away my-client-status)
        my-battle-status (fx/sub-ctx context sub/my-battle-status server-key)
        my-team-color (fx/sub-ctx context sub/my-team-color server-key)
        direct-connect #p (= :direct server-key)
        my-sync-status (fx/sub-ctx context sub/my-sync-status server-key)
        in-sync (= 1 (:sync my-battle-status))
        ringing-specs (seq (fx/sub-ctx context skylobby.fx/tasks-of-type-sub :spring-lobby/ring-specs))
        discord-channel (discord/channel-to-promote {:mod-name mod-name
                                                     :server-url (:server-url client-data)})
        now (fx/sub-val context :now)
        discord-promoted (fx/sub-val context get-in [:discord-promoted discord-channel])
        discord-promoted-diff (when discord-promoted (- now discord-promoted))
        discord-promote-cooldown (boolean (and discord-promoted-diff
                                               (< discord-promoted-diff discord/cooldown)))
        sync-button-style (dissoc
                            (case my-sync-status
                              1 ok-severity
                              2 error-severity
                              ; else
                              warn-severity)
                            :-fx-background-color
                            :-fx-font-size)
        sync-buttons (if-not singleplayer
                       [{:fx/type :h-box
                         :alignment :center-left
                         :style {:-fx-font-size 16}
                         :children
                         [{:fx/type sync-button
                           :server-key server-key}
                          {:fx/type :button
                           :text ""
                           :graphic {:fx/type font-icon/lifecycle
                                     :icon-literal (if battle-resource-details
                                                     (str "mdi-window-maximize:" (+ 5 font-icon-size) ":white")
                                                     (str "mdi-open-in-new:" (+ 5 font-icon-size) ":white"))}
                           :on-action {:event/type :spring-lobby/assoc
                                       :key :battle-resource-details
                                       :value (not (boolean battle-resource-details))}
                           :style sync-button-style}]}]
                       [{:fx/type :button
                         :text "Reload"
                         :on-action {:event/type :spring-lobby/clear-map-and-mod-details
                                     :map-resource indexed-map
                                     :mod-resource indexed-mod}}])
        buttons (concat
                  (when-not am-host
                    [{:fx/type :button
                      :text "Balance"
                      :on-action {:event/type :spring-lobby/battle-balance
                                  :am-host am-host
                                  :battle battle
                                  :channel-name channel-name
                                  :client-data (when-not singleplayer client-data)
                                  :users users
                                  :username username}}])
                  [{:fx/type :button
                    :text "Fix Colors"
                    :on-action {:event/type :spring-lobby/battle-fix-colors
                                :am-host am-host
                                :battle battle
                                :channel-name channel-name
                                :client-data (when-not singleplayer client-data)
                                :users users
                                :username username}}]
                  (when-not singleplayer
                    [{:fx/type :button
                      :text "Ring Specs"
                      :disable (boolean ringing-specs)
                      :on-action
                      {:event/type :spring-lobby/add-task
                       :task
                       {:spring-lobby/task-type :spring-lobby/ring-specs
                        :battle-users (:users battle)
                        :channel-name channel-name
                        :client-data client-data
                        :users users}}}
                     {:fx/type :button
                      :text (if discord-channel
                              (if discord-promote-cooldown
                                (str "Promote every " (.toMinutesPart (java-time/duration discord/cooldown :millis)) "m")
                                "Promote to Discord")
                              "Promote")
                      :disable discord-promote-cooldown
                      :on-action
                      (if discord-channel
                        {:event/type :spring-lobby/promote-discord
                         :discord-channel discord-channel
                         :data {:battle-title (get-in battles [battle-id :battle-title])
                                :map-name map-name
                                :mod-name mod-name
                                :team-counts team-counts}
                         :server-key server-key}
                        {:event/type :spring-lobby/send-message
                         :channel-name channel-name
                         :client-data client-data
                         :message "!promote"
                         :server-key server-key})}])
                  (when am-host
                    [{:fx/type :h-box
                      :alignment :center-left
                      :children
                      [
                       {:fx/type :pane
                        :style {:-fx-pref-width 8}}
                       {:fx/type :check-box
                        :selected (boolean interleave-ally-player-ids)
                        :on-selected-changed {:event/type :spring-lobby/assoc
                                              :key :interleave-ally-player-ids}}
                       {:fx/type :label
                        :text " Interleave Player IDs "}]}]))
        debug-spring (boolean (fx/sub-val context :debug-spring))]
    {:fx/type :v-box
     :children
     [
      {:fx/type :flow-pane
       :style {:-fx-font-size 16}
       :hgap 4
       :orientation :horizontal
       :children
       (concat
         sync-buttons
         [{:fx/type :pane
           :pref-width 16}]
         buttons
         (when (or singleplayer (not am-spec))
           [{:fx/type :h-box
             :alignment :center-left
             :children
             [{:fx/type fx/ext-let-refs
               :refs {::add-bot-button
                      {:fx/type :button
                       :text "Add AI"
                       :disable (boolean (fx/sub-val context :show-add-bot))
                       :on-action {:event/type :spring-lobby/toggle
                                   :key :show-add-bot}}}
               :desc {:fx/type fx/ext-let-refs
                      :refs {::add-bot-popup {:fx/type ext-with-shown-on
                                              :props (when (fx/sub-val context :show-add-bot)
                                                       {:shown-on {:fx/type fx/ext-get-ref :ref ::add-bot-button}})
                                              :desc {:fx/type :tooltip
                                                     :anchor-location :window-bottom-left
                                                     :auto-hide true
                                                     :auto-fix true
                                                     :on-hidden {:event/type :spring-lobby/dissoc
                                                                 :key :show-add-bot}
                                                     :graphic
                                                     {:fx/type :v-box
                                                      :style {:-fx-font-size 16}
                                                      :children
                                                      (concat
                                                        [
                                                         {:fx/type :h-box
                                                          :alignment :center-left
                                                          :children
                                                          [
                                                           {:fx/type :label
                                                            :text " AI: "}
                                                           {:fx/type :combo-box
                                                            :value bot-name
                                                            :disable (empty? bot-names)
                                                            :on-value-changed {:event/type :spring-lobby/change-bot-name
                                                                               :bots bots}
                                                            :items (sort bot-names)}]}
                                                         {:fx/type :h-box
                                                          :alignment :center-left
                                                          :children
                                                          [
                                                           {:fx/type :label
                                                            :text " Version: "}
                                                           {:fx/type ext-recreate-on-key-changed
                                                            :key (str bot-name)
                                                            :desc
                                                            {:fx/type :combo-box
                                                             :value bot-version
                                                             :disable (string/blank? bot-name)
                                                             :on-value-changed {:event/type :spring-lobby/assoc
                                                                                :key :bot-version}
                                                             :items (or bot-versions [])}}]}
                                                         {:fx/type :h-box
                                                          :alignment :center-left
                                                          :children
                                                          [{:fx/type :label
                                                            :text " Name: "}
                                                           {:fx/type :text-field
                                                            :prompt-text "AI Name"
                                                            :text (str bot-username)
                                                            :on-text-changed {:event/type :spring-lobby/assoc
                                                                              :key :bot-username}}]}]
                                                        (when (re-find #"\s" bot-username)
                                                          [{:fx/type :label
                                                            :style {:-fx-text-fill "red"}
                                                            :text "Name cannot contain whitespace"}])
                                                        [{:fx/type :button
                                                          :text "Add"
                                                          :disable (boolean
                                                                     (or
                                                                       (string/blank? bot-name)
                                                                       (string/blank? bot-version)
                                                                       (string/blank? bot-username)
                                                                       (re-find #"\s" bot-username)))
                                                          :on-action
                                                          {:event/type :spring-lobby/add-bot
                                                           :battle battle
                                                           :bot-username bot-username
                                                           :bot-name bot-name
                                                           :bot-version bot-version
                                                           :client-data client-data
                                                           :server-key server-key
                                                           :side-indices (keys sides)
                                                           :singleplayer singleplayer
                                                           :username username}}])}}}}
                      :desc {:fx/type fx/ext-get-ref :ref ::add-bot-button}}}]}])
         [{:fx/type :h-box
           :alignment :center-left
           :children
           (concat
             (when (and am-host (not singleplayer))
               (let [filter-replay-lc (if filter-host-replay
                                        (string/lower-case filter-host-replay)
                                        "")]
                 [{:fx/type :label
                   :text " Replay: "}
                  {:fx/type :combo-box
                   :prompt-text " < host a replay > "
                   :style {:-fx-max-width 300}
                   :value (get-in scripttags ["game" "demofile"])
                   :on-value-changed {:event/type :spring-lobby/assoc-in
                                      :path [:by-server server-key :battle :scripttags "game" "demofile"]}
                   :on-key-pressed {:event/type :spring-lobby/host-replay-key-pressed}
                   :on-hidden {:event/type :spring-lobby/dissoc
                               :key :filter-host-replay}
                   :items (->> parsed-replays-by-path
                               (filter (comp :filename second))
                               (filter (comp #(string/includes? (string/lower-case %) filter-replay-lc)
                                             :filename
                                             second))
                               (sort-by (comp :filename second))
                               reverse
                               (mapv first))
                   :button-cell (fn [path] {:text (str (some-> path io/file fs/filename))})}]))
             (when (get-in scripttags ["game" "demofile"])
               [{:fx/type :button
                 :on-action {:event/type :spring-lobby/dissoc-in
                             :path [:battle :scripttags "game" "demofile"]}
                 :graphic
                 {:fx/type font-icon/lifecycle
                  :icon-literal (str "mdi-close:" font-icon-size ":white")}}]))}
          {:fx/type :pane
           :pref-width 16}]
         (when (and am-spec
                    (not singleplayer))
           [{:fx/type :h-box
             :alignment :center-left
             :children
             [{:fx/type ext-recreate-on-key-changed
               :key (str am-spec)
               :desc
               {:fx/type :check-box
                :selected (boolean auto-launch)
                :style {:-fx-padding "10px"}
                :on-selected-changed {:event/type :spring-lobby/assoc-in
                                      :path [:auto-launch server-key]}}}
              {:fx/type :label
               :text "Auto Launch "}]}])
         (when-not singleplayer
           [{:fx/type :h-box
             :alignment :center-left
             :style {:-fx-font-size 16}
             :children
             [{:fx/type ext-recreate-on-key-changed
               :key (str [am-spec am-away])
               :desc
               {:fx/type :combo-box
                :value (if am-away "Away" "Here")
                :items ["Away" "Here"]
                :on-value-changed {:event/type :spring-lobby/on-change-away
                                   :client-data (when-not singleplayer client-data)
                                   :client-status (assoc my-client-status :away (not am-away))}}}]}])
         [{:fx/type :h-box
           :alignment :center-left
           :style {:-fx-font-size 16}
           :children
           [{:fx/type ext-recreate-on-key-changed
             :key (str [am-spec am-away])
             :desc
             {:fx/type :combo-box
              :value (if am-spec "Spectating" "Playing")
              :items ["Spectating" "Playing"]
              :on-value-changed {:event/type :spring-lobby/on-change-spectate
                                 :client-data (when-not singleplayer client-data)
                                 :is-me true
                                 :is-bot false
                                 :id my-player
                                 :ready-on-unspec ready-on-unspec
                                 :server-key server-key}}}]}
          {:fx/type :h-box
           :alignment :center-left
           :style {:-fx-font-size 24}
           :children
           (if-not am-spec
             [(let [ready (:ready my-battle-status)]
                {:fx/type ext-recreate-on-key-changed
                 :key (str am-spec)
                 :desc
                 {:fx/type :check-box
                  :selected (boolean ready)
                  :style {:-fx-padding "10px"}
                  :on-selected-changed {:event/type :spring-lobby/battle-ready-change
                                        :client-data client-data
                                        :username username
                                        :battle-status my-battle-status
                                        :team-color my-team-color}}})
              {:fx/type :label
               :text " Ready "}]
             (if (contains? (:compflags client-data) "teiserver")
               [{:fx/type :h-box
                 :style {:-fx-font-size 16}
                 :alignment :center-left
                 :children
                 [
                  {:fx/type battle-queue-button
                   :server-key server-key}]}]
               [{:fx/type ext-recreate-on-key-changed
                 :key (str am-spec)
                 :desc
                 {:fx/type :check-box
                  :selected (boolean auto-unspec)
                  :style {:-fx-padding "10px"
                          :-fx-font-size 15}
                  :on-selected-changed
                  {:event/type :spring-lobby/auto-unspec
                   :client-data (when-not singleplayer client-data)
                   :is-me true
                   :is-bot false
                   :id my-player
                   :ready-on-unspec ready-on-unspec
                   :server-key server-key}}}
                {:fx/type :label
                 :style {:-fx-font-size 15}
                 :text "Auto Unspec "}]))}
          {:fx/type :h-box
           :alignment :center-left
           :style {:-fx-font-size 24}
           :children
           (concat
             [
              {:fx/type fx.ext.node/with-tooltip-props
               :props
               {:tooltip
                {:fx/type tooltip-nofocus/lifecycle
                 :show-delay skylobby.fx/tooltip-show-delay
                 :style {:-fx-font-size 16}
                 :text (cond
                         debug-spring "Write script.txt and show Spring command"
                         am-host (if singleplayer
                                   "Start the game"
                                   "You are the host, start the game")
                         host-ingame "Join game in progress"
                         :else (str "Call vote to start the game"))}}
               :desc
               {:fx/type :button
                :text (cond
                        spring-starting
                        "Game starting"
                        spring-running
                        "Game running"
                        (and am-spec (not host-ingame) (not singleplayer))
                        "Game not running"
                        :else
                        (if debug-spring
                          "Debug Spring"
                          (str (if (and (not singleplayer) (or host-ingame am-spec))
                                 "Join" "Start")
                               " Game")))
                :disable (boolean
                           (or spring-starting
                               spring-running
                               (and debug-spring (fx/sub-val context :show-spring-debug))
                               (and (not singleplayer)
                                    (or (and (not host-ingame) am-spec)
                                        (not in-sync)))))
                :on-action
                (merge
                  {:event/type :spring-lobby/start-battle}
                  (let [resources (fx/sub-ctx context sub/spring-resources spring-root)]
                    (if singleplayer
                      resources
                      (dissoc resources :engine-version :map-name :mod-name)))
                  {:battle battle
                   :battles battles
                   :users users
                   :username username}
                  {:am-host am-host
                   :am-spec am-spec
                   :battle-map-details battle-map-details
                   :battle-mod-details battle-mod-details
                   :battle-status my-battle-status
                   :channel-name channel-name
                   :client-data client-data
                   :debug-spring debug-spring
                   :host-ingame host-ingame
                   :singleplayer singleplayer
                   :spring-isolation-dir spring-root})}}]
             (when (and spring-running singleplayer)
               [{:fx/type fx.ext.node/with-tooltip-props
                 :props
                 {:tooltip
                  {:fx/type tooltip-nofocus/lifecycle
                   :show-delay skylobby.fx/tooltip-show-delay
                   :style {:-fx-font-size 16}
                   :text "Allow another copy of spring to run"}}
                 :desc
                 {:fx/type :button
                  :style-class ["button" "skylobby-normal"]
                  :text ""
                  :on-action
                  {:event/type :spring-lobby/assoc-in
                   :path [:spring-running :local battle-id]
                   :value false}
                  :graphic
                  {:fx/type font-icon/lifecycle
                   :icon-literal "mdi-content-copy:20"}}}]))}]
         (when am-host
           [{:fx/type :h-box
             :alignment :center-left
             :children
             [{:fx/type :label
               :text " Balance: "}
              {:fx/type :button
               :text "FFA"
               :on-action {:event/type :spring-lobby/battle-teams-ffa
                           :am-host am-host
                           :battle battle
                           :client-data (when-not singleplayer client-data)
                           :interleave-ally-player-ids interleave-ally-player-ids
                           :server-key server-key
                           :users users
                           :username username}}
              {:fx/type :button
               :text "2 teams"
               :on-action {:event/type :spring-lobby/battle-teams-2
                           :am-host am-host
                           :battle battle
                           :client-data (when-not singleplayer client-data)
                           :interleave-ally-player-ids interleave-ally-player-ids
                           :server-key server-key
                           :users users
                           :username username}}
              {:fx/type :button
               :text "3 teams"
               :on-action {:event/type :spring-lobby/battle-teams-3
                           :am-host am-host
                           :battle battle
                           :client-data (when-not singleplayer client-data)
                           :interleave-ally-player-ids interleave-ally-player-ids
                           :server-key server-key
                           :users users
                           :username username}}
              {:fx/type :button
               :text "4 teams"
               :on-action {:event/type :spring-lobby/battle-teams-4
                           :am-host am-host
                           :battle battle
                           :client-data (when-not singleplayer client-data)
                           :interleave-ally-player-ids interleave-ally-player-ids
                           :server-key server-key
                           :users users
                           :username username}}
              {:fx/type :button
               :text "5 teams"
               :on-action {:event/type :spring-lobby/battle-teams-5
                           :am-host am-host
                           :battle battle
                           :client-data (when-not singleplayer client-data)
                           :interleave-ally-player-ids interleave-ally-player-ids
                           :server-key server-key
                           :users users
                           :username username}}
              {:fx/type :button
               :text "Humans vs Bots"
               :on-action {:event/type :spring-lobby/battle-teams-humans-vs-bots
                           :am-host am-host
                           :battle battle
                           :client-data (when-not singleplayer client-data)
                           :interleave-ally-player-ids interleave-ally-player-ids
                           :server-key server-key
                           :users users
                           :username username}}]}])
         [{:fx/type :label
           :style {:-fx-font-size 24}
           :text (str " "
                      (when (< 1 (count team-counts))
                        (string/join "v" team-counts)))}]
         (when (< 4 (count team-counts))
           [{:fx/type :label
             :style {:-fx-font-size 16}
             :text (str " " (count team-counts) "-way ffa")}])
         (when show-team-skills
           [{:fx/type :label
             :style {:-fx-font-size 16}
             :text (str " "
                        (when (< 1 (count team-skills))
                          (str
                            "("
                            (string/join ", " team-skills)
                            ")")))}]))}
      {:fx/type battle-accolades
       :server-key server-key}]}))


(defn battle-tabs
  [{:fx/keys [context]
    :keys [server-key]}]
  (let [
        am-host (fx/sub-ctx context sub/am-host server-key)
        host-username (fx/sub-ctx context sub/host-username server-key)
        am-spec (fx/sub-ctx context sub/am-spec server-key)
        {:keys [battle-id] :as battle} (fx/sub-val context get-in [:by-server server-key :battle])
        mod-name (fx/sub-val context get-in [:by-server server-key :battles battle-id :battle-modname])
        map-name (fx/sub-val context get-in [:by-server server-key :battles battle-id :battle-map])
        spring-isolation-dir (fx/sub-ctx context sub/spring-root server-key)
        indexed-map (fx/sub-ctx context sub/indexed-map spring-isolation-dir map-name)
        indexed-mod (fx/sub-ctx context sub/indexed-mod spring-isolation-dir mod-name)
        battle-map-details (fx/sub-ctx context skylobby.fx/map-details-sub indexed-map)
        battle-mod-details (fx/sub-ctx context skylobby.fx/mod-details-sub indexed-mod)
        channel-name (fx/sub-ctx context skylobby.fx/battle-channel-sub server-key)
        client-data (fx/sub-val context get-in [:by-server server-key :client-data])
        file-cache (fx/sub-val context :file-cache)
        scripttags (fx/sub-val context get-in [:by-server server-key :battle :scripttags])
        singleplayer (= :local server-key)
        spring-settings (fx/sub-val context :spring-settings)
        startpostype (fx/sub-ctx context sub/startpostype server-key)
        username (fx/sub-val context get-in [:by-server server-key :username])
        users (fx/sub-val context get-in [:by-server server-key :users])
        minimap-size (fx/sub-val context :minimap-size)
        minimap-size (or (u/to-number minimap-size)
                         fx.minimap/default-minimap-size)]
    {:fx/type :tab-pane
     :style {:-fx-min-width (+ minimap-size 20)
             :-fx-pref-width (+ minimap-size 20)
             :-fx-max-width (+ minimap-size 20)
             :-fx-pref-height (+ minimap-size 164)}
     :tabs
     [{:fx/type :tab
       :graphic {:fx/type :label
                 :text "map"}
       :closable false
       :content
       {:fx/type :scroll-pane
        :fit-to-width true
        :hbar-policy :never
        :vbar-policy :always
        :content
        {:fx/type :v-box
         :alignment :top-left
         :children
         [{:fx/type fx.minimap/minimap-pane
           :server-key server-key
           :minimap-type-key :minimap-type}
          {:fx/type :v-box
           :children
           (concat
             [
              (let [{:keys [battle-status]} (-> battle :users (get username))
                    disable (boolean (and (not singleplayer) am-spec))]
                {:fx/type maps-view
                 :action-disable-rotate {:event/type :spring-lobby/send-message
                                         :channel-name channel-name
                                         :client-data client-data
                                         :message "!rotationEndGame off"
                                         :server-key server-key}
                 :disable disable
                 :flow true
                 :map-name map-name
                 :spring-isolation-dir spring-isolation-dir
                 :text-only disable
                 :on-value-changed
                 (cond
                   singleplayer
                   {:event/type :spring-lobby/assoc-in
                    :path [:by-server server-key :battles :singleplayer :battle-map]}
                   am-host
                   {:event/type :spring-lobby/battle-map-change
                    :client-data client-data}
                   :else
                   {:event/type :spring-lobby/suggest-battle-map
                    :battle-status battle-status
                    :channel-name channel-name
                    :client-data client-data})})
              (let [map-description (str (-> battle-map-details :mapinfo :description))]
                {:fx/type fx.ext.node/with-tooltip-props
                 :props
                 {:tooltip
                  {:fx/type tooltip-nofocus/lifecycle
                   :show-delay skylobby.fx/tooltip-show-delay
                   :style {:-fx-font-size 16}
                   :text map-description}}
                 :desc
                 {:fx/type :label
                  :text map-description}})
              {:fx/type :flow-pane
               :children
               [
                {:fx/type :label
                 :text (str " Display (px): ")}
                {:fx/type :combo-box
                 :value minimap-size
                 :items fx.minimap/minimap-sizes
                 :on-value-changed {:event/type :spring-lobby/assoc
                                    :key :minimap-size}}
                {:fx/type :combo-box
                 :value (fx/sub-val context :minimap-type)
                 :items minimap-types
                 :on-value-changed {:event/type :spring-lobby/assoc
                                    :key :minimap-type}}
                {:fx/type :label
                 :text (str " Map size: "
                            (when-let [{:keys [map-width map-height]} (-> battle-map-details :smf :header)]
                              (str
                                (when map-width (quot map-width 64))
                                " x "
                                (when map-height (quot map-height 64)))))}]}
              {:fx/type :flow-pane
               :children
               (concat
                 [{:fx/type :label
                   :alignment :center-left
                   :text " Start Positions: "}
                  {:fx/type :combo-box
                   :value startpostype
                   :items (map str (vals spring/startpostypes))
                   :disable (and (not singleplayer) am-spec)
                   :on-value-changed {:event/type :spring-lobby/battle-startpostype-change
                                      :am-host am-host
                                      :channel-name channel-name
                                      :client-data client-data
                                      :singleplayer singleplayer}}]
                 (when (= "Choose before game" startpostype)
                   [{:fx/type :button
                     :text "Reset"
                     :disable (and (not singleplayer) am-spec)
                     :on-action {:event/type :spring-lobby/reset-start-positions
                                 :client-data client-data
                                 :server-key server-key}}])
                 (when (= "Choose in game" startpostype)
                   [{:fx/type :button
                     :text "Clear boxes"
                     :disable (and (not singleplayer) am-spec)
                     :on-action {:event/type :spring-lobby/clear-start-boxes
                                 :allyteam-ids (->> (get scripttags "game")
                                                    (filter (comp #(string/starts-with? % "allyteam") name first))
                                                    (map
                                                      (fn [[teamid _team]]
                                                        (let [[_all id] (re-find #"allyteam(\d+)" (name teamid))]
                                                          id))))
                                 :client-data client-data
                                 :server-key server-key}}]))}]
             (when (and (not am-host)
                        (-> users (get host-username) :client-status :bot))
               [{:fx/type :button
                 :text "List Maps"
                 :on-action {:event/type :spring-lobby/send-message
                             :channel-name channel-name
                             :client-data client-data
                             :message "!listmaps"
                             :server-key server-key}}]))}]}}}
      {:fx/type :tab
       :graphic {:fx/type :label
                 :text "modoptions"}
       :closable false
       :content
       {:fx/type :v-box
        :alignment :top-left
        :children
        [{:fx/type fx.spring-options/modoptions-view
          :modoptions (:modoptions battle-mod-details)
          :server-key server-key
          :singleplayer singleplayer}]}}
      {:fx/type :tab
       :graphic {:fx/type :label
                 :text "mapoptions"}
       :closable false
       :content
       {:fx/type :v-box
        :alignment :top-left
        :children
        [{:fx/type fx.spring-options/modoptions-view
          :modoptions (:mapoptions battle-map-details)
          :option-key "mapoptions"
          :server-key server-key
          :singleplayer singleplayer}]}}
      {:fx/type :tab
       :graphic {:fx/type :label
                 :text "Spring settings"}
       :closable false
       :content
       (let [{:keys [auto-backup backup-name confirmed game-specific results]} spring-settings
             spring-settings-dir (fs/spring-settings-root)
             dest-dir (when-not (string/blank? backup-name)
                        (fs/file spring-settings-dir backup-name))]
         {:fx/type :v-box
          :children
          [
           {:fx/type :label
            :text " Auto Manage "
            :style {:-fx-font-size 20}}
           {:fx/type :pane
            :style {:-fx-min-height 20
                    :-fx-pref-height 20}}
           {:fx/type :label
            :text " Includes springsettings.cfg, LuiUI/Config, and uikeys.txt"}
           {:fx/type :h-box
            :alignment :center-left
            :children
            [
             {:fx/type :check-box
              :selected (boolean auto-backup)
              :on-selected-changed {:event/type :spring-lobby/assoc-in
                                    :path [:spring-settings :auto-backup]}}
             {:fx/type :label
              :text " Auto Backup "}]}
           {:fx/type :label
            :min-height :use-pref-size
            :wrap-text true
            :text (str " If enabled, will copy these files into a backup folder in "
                       spring-settings-dir
                       " named 'backup-yyyyMMdd-HHmmss' before Spring is run.")}
           {:fx/type :pane
            :style {:-fx-margin-top 8
                    :-fx-margin-bottom 8}}
           {:fx/type :h-box
            :alignment :center-left
            :children
            [
             {:fx/type :check-box
              :selected (boolean game-specific)
              :on-selected-changed {:event/type :spring-lobby/assoc-in
                                    :path [:spring-settings :game-specific]}}
             {:fx/type :label
              :text " Game specific settings"}]}
           {:fx/type :label
            :min-height :use-pref-size
            :wrap-text true
            :text (str " If enabled, will copy these files from a game specific folder in "
                       spring-settings-dir
                       " before Spring is run, and save them there after Spring exits."
                       " May cause issues running multiple instances of Spring at once.")}
           {:fx/type :pane
            :style {:-fx-min-height 40
                    :-fx-pref-height 40}}
           {:fx/type :label
            :text " Manual Backup "
            :style {:-fx-font-size 20}}
           {:fx/type :h-box
            :alignment :center-left
            :children
            [{:fx/type :label
              :text " Name: "}
             {:fx/type :text-field
              :text (str (:backup-name spring-settings))
              :on-text-changed {:event/type :spring-lobby/assoc-in
                                :path [:spring-settings :backup-name]}}]}
           {:fx/type :button
            :text " Backup! "
            :disable (boolean (or (string/blank? backup-name)
                                  (and (not confirmed)
                                       (fs/file-exists? file-cache dest-dir))))
            :on-action {:event/type :spring-lobby/spring-settings-copy
                        :confirmed confirmed
                        :dest-dir dest-dir
                        :file-cache file-cache
                        :source-dir spring-isolation-dir}}
           {:fx/type :h-box
            :alignment :center-left
            :children
            [{:fx/type :check-box
              :selected (boolean confirmed)
              :on-selected-changed {:event/type :spring-lobby/assoc-in
                                    :path [:spring-settings :confirmed]}}
             {:fx/type :label
              :text " OVERWRITE EXISTING"}]}
           {:fx/type :v-box
            :children
            (map
              (fn [[path result]]
                {:fx/type :label
                 :text (str " " path " "
                            (case result
                              :copied "was copied"
                              :does-not-exist "does not exist"
                              :error "errored"
                              "unknown"))})
              (get results (fs/canonical-path spring-isolation-dir)))}
           {:fx/type :pane
            :style {:-fx-min-height 40
                    :-fx-pref-height 40}}
           {:fx/type :label
            :text " Restore "
            :style {:-fx-font-size 20}}
           {:fx/type :h-box
            :alignment :center-left
            :children
            [
             {:fx/type :button
              :text ""
              :on-action {:event/type :spring-lobby/spring-settings-refresh}
              :graphic
              {:fx/type font-icon/lifecycle
               :icon-literal "mdi-refresh:16:white"}}
             {:fx/type :label
              :text (str " From " (fs/spring-settings-root))}]}
           {:fx/type :table-view
            :v-box/vgrow :always
            :column-resize-policy :constrained
            :items (or (some->> (fs/spring-settings-root)
                                fs/list-files ; TODO IO in render
                                (filter fs/is-directory?)
                                reverse)
                       [])
            :columns
            [{:fx/type :table-column
              :text "Directory"
              :cell-value-factory fs/filename
              :cell-factory
              {:fx/cell-type :table-cell
               :describe
               (fn [filename]
                 {:text (str filename)})}}
             {:fx/type :table-column
              :text "Action"
              :cell-value-factory identity
              :cell-factory
              {:fx/cell-type :table-cell
               :describe
               (fn [i]
                 {:text (when (get results (fs/canonical-path i))
                          " copied!")
                  :graphic
                  {:fx/type :button
                   :text "Restore"
                   :on-action
                   {:event/type :spring-lobby/spring-settings-copy
                    :confirmed true ; TODO confirm
                    :dest-dir spring-isolation-dir
                    :file-cache file-cache
                    :source-dir i}}})}}]}]})}
      #_
      {:fx/type :tab
       :graphic {:fx/type :label
                 :text "uikeys"}
       :closable false
       :content
       {:fx/type :v-box
        :children
        [{:fx/type :button
          :text "show window"
          :on-action {:event/type :spring-lobby/assoc
                      :key :show-uikeys-window}}]}}
      #_
      {:fx/type :tab
       :graphic {:fx/type :label
                 :text "script.txt"}
       :closable false
       :content
       {:fx/type :v-box
        :children
        [{:fx/type :text-area
          :v-box/vgrow :always
          :style {:-fx-font-family "Monospace"}
          :text (str (spring/battle-script-txt
                       (assoc
                         (fx/sub-val context get-in [:by-server server-key])
                         :battle-map-details battle-map-details
                         :battle-mod-details battle-mod-details)))}]}}]}))


(defn battle-votes-impl
  [{:fx/keys [context]
    :keys [server-key]}]
  (let [show-vote-log (fx/sub-val context :show-vote-log)
        client-data (fx/sub-val context get-in [:by-server server-key :client-data])
        channel-name (fx/sub-ctx context skylobby.fx/battle-channel-sub server-key)
        messages (fx/sub-val context get-in [:by-server server-key :channels channel-name :messages])
        host-username (fx/sub-ctx context sub/host-username server-key)
        host-messages (->> messages
                           (filter (comp #{host-username} :username)))
        host-ex-messages (->> host-messages
                              (filter (comp #{:ex} :message-type)))
        spads-messages (->> host-ex-messages
                            (filter :spads))
        vote-messages (->> spads-messages
                           (filter (comp #{:called-vote :cancelling-vote :game-starting-cancel :no-vote :vote-cancelled
                                           :vote-cancelled-game-launch :vote-failed :vote-passed :vote-progress}
                                         :spads-message-type
                                         :spads))
                           (map
                             (fn [{:keys [spads] :as message}]
                               (let [{:keys [spads-message-type spads-parsed]} spads]
                                 (assoc-in message [:spads :vote-data]
                                   (if (= :called-vote spads-message-type)
                                     {:command (nth spads-parsed 2)
                                      :caller (second spads-parsed)}
                                     {:command (second spads-parsed)}))))))
        current-vote (reduce
                       (fn [prev {:keys [spads] :as curr}]
                         (let [{:keys [spads-message-type spads-parsed]} spads]
                           (cond
                             (= :called-vote spads-message-type) curr
                             (= :vote-progress spads-message-type)
                             (let [[_all _command y yt n nt _ remaining] spads-parsed]
                               (assoc-in prev [:spads :vote-progress] {:y y
                                                                       :yt yt
                                                                       :n n
                                                                       :nt nt
                                                                       :remaining remaining}))
                             (#{:cancelling-vote :game-starting-cancel :no-vote :vote-cancelled
                                :vote-cancelled-game-launch :vote-failed :vote-passed}
                               spads-message-type) nil
                             :else prev)))
                       nil
                       (reverse vote-messages))
        minimap-size (fx/sub-val context :minimap-size)
        minimap-size (or (u/to-number minimap-size)
                         fx.minimap/default-minimap-size)]
    {:fx/type :v-box
     :children
     (concat
       (when-let [{:keys [spads]} current-vote]
         (let [{:keys [vote-data vote-progress]} spads
               {:keys [y yt n nt remaining]} vote-progress]
           [{:fx/type :v-box
             :style {:-fx-font-size 18}
             :children
             [{:fx/type :label
               :style {:-fx-font-size 24}
               :text "Current Vote"}
              {:fx/type :label
               :text (str " " (:command vote-data))}
              {:fx/type :label
               :text (str " by " (:caller vote-data))}
              {:fx/type :h-box
               :children
               (concat
                 [
                  {:fx/type :label
                   :text (str " "
                              (when vote-progress
                                (str "Y: " y " / " yt "  N: " n " / " nt)))}]
                 (when remaining (str remaining " left")
                   [{:fx/type :pane
                     :h-box/hgrow :always}
                    {:fx/type :label
                     :text (str remaining " left")}]))}
              {:fx/type :h-box
               :children
               [{:fx/type :button
                 :style (assoc (dissoc ok-severity :-fx-background-color)
                               :-fx-font-size 20)
                 :text "Yes"
                 :on-action {:event/type :spring-lobby/send-message
                             :channel-name channel-name
                             :client-data client-data
                             :message "!vote y"
                             :server-key server-key}}
                {:fx/type :pane
                 :h-box/hgrow :always}
                {:fx/type :button
                 :style (assoc (dissoc error-severity :-fx-background-color)
                               :-fx-font-size 20)
                 :text "No"
                 :on-action {:event/type :spring-lobby/send-message
                             :channel-name channel-name
                             :client-data client-data
                             :message "!vote n"
                             :server-key server-key}}
                {:fx/type :pane
                 :h-box/hgrow :always}
                {:fx/type :button
                 :text "Present"
                 :style {:-fx-font-size 20}
                 :on-action {:event/type :spring-lobby/send-message
                             :channel-name channel-name
                             :client-data client-data
                             :message "!vote b"
                             :server-key server-key}}]}]}]))
       [{:fx/type :h-box
         :children
         (concat
           (when show-vote-log
             [{:fx/type :label
               :text "Vote Log (newest to oldest)"
               :style {:-fx-font-size 24}}])
           [{:fx/type :pane
             :h-box/hgrow :always}
            {:fx/type :button
             :text ""
             :style-class ["button" "skylobby-normal"]
             :on-action {:event/type (if show-vote-log :spring-lobby/dissoc :spring-lobby/assoc)
                         :key :show-vote-log}
             :graphic
             {:fx/type font-icon/lifecycle
              :icon-literal (if show-vote-log
                              "mdi-arrow-down:16"
                              "mdi-arrow-up:16")}}])}]
       (when show-vote-log
         [{:fx/type :scroll-pane
           :pref-width (+ minimap-size 20)
           :v-box/vgrow :always
           :content
           {:fx/type :v-box
            :style {:-fx-font-size 18}
            :children
            (->> vote-messages
                 (filter (comp #{:called-vote :cancelling-vote :game-starting-cancel :vote-cancelled :vote-failed :vote-passed} :spads-message-type :spads))
                 (mapv
                   (fn [{:keys [spads timestamp]}]
                     (let [{:keys [spads-message-type vote-data]} spads]
                       {:fx/type :h-box
                        :alignment :center-left
                        :children
                        (concat
                          [{:fx/type :label
                            :style {:-fx-font-family monospace-font-family}
                            :text (str (when (number? timestamp) (u/format-hours timestamp))
                                       " ")}
                           {:fx/type font-icon/lifecycle
                            :icon-literal (case spads-message-type
                                            :called-vote "mdi-phone:16:blue"
                                            :vote-passed "mdi-phone-incoming:16:green"
                                            :vote-failed "mdi-phone-missed:16:red"
                                            :cancelling-vote "mdi-phone-minus:16:gold"
                                            :vote-cancelled "mdi-phone-minus:16:gold"
                                            :vote-cancelled-game-launch "mdi-phone-minus:16:gold"
                                            :game-starting-cancel "mdi-phone-minus:16:gold"
                                            ; else
                                            "mdi-phone:16:white")}]
                          (when (= :called-vote spads-message-type)
                            [
                             {:fx/type :label
                              :style {:-fx-font-family monospace-font-family}
                              :text (str " " (:caller vote-data) ":")}])
                          [{:fx/type :label
                            :style {:-fx-font-family monospace-font-family}
                            :text (str " " (:command vote-data))}])}))))}}]))}))

(defn battle-votes
  [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :battle-votes
      (battle-votes-impl state))))


(defn battle-players-and-bots
  "Returns the sequence of all players and bots for a battle."
  [{:keys [battle users]}]
  (concat
    (mapv
      (fn [[k v]] (assoc v :username k :user (get users k)))
      (:users battle))
    (mapv
      (fn [[k v]]
        (assoc v
               :bot-name k
               :user {:client-status {:bot true}}))
      (:bots battle))))


(defn battle-view-impl
  [{:fx/keys [context]
    :keys [battle-id server-key]}]
  (let [
        battle-layout (fx/sub-val context :battle-layout)
        divider-positions (fx/sub-val context :divider-positions)
        pop-out-chat (fx/sub-val context :pop-out-chat)
        server-url (fx/sub-val context get-in [:by-server server-key :client-data :server-url])
        old-battle (fx/sub-val context get-in [:by-server server-key :old-battles battle-id])
        battle-id (or battle-id
                      (fx/sub-val context get-in [:by-server server-key :battle :battle-id]))
        scripttags (or (:scripttags old-battle)
                       (fx/sub-val context get-in [:by-server server-key :battle :scripttags]))
        engine-version (fx/sub-val context get-in [:by-server server-key :battles battle-id :battle-version])
        mod-name (fx/sub-val context get-in [:by-server server-key :battles battle-id :battle-modname])
        map-name (fx/sub-val context get-in [:by-server server-key :battles battle-id :battle-map])
        spring-root (fx/sub-ctx context skylobby.fx/spring-root-sub server-url)
        {:keys [engines-by-version mods-by-name]} (fx/sub-ctx context sub/spring-resources spring-root)
        engine-details (get engines-by-version engine-version)
        mod-dependencies (->> mod-name
                              resource/mod-dependencies
                              (map (fn [mod-name]
                                     (let [indexed-mod (get mods-by-name mod-name)]
                                       {:mod-name mod-name
                                        :indexed indexed-mod
                                        :details indexed-mod}))))
        engine-file (:file engine-details)
        battle-users (or (:users old-battle)
                         (fx/sub-val context get-in [:by-server server-key :battle :users]))
        battle-bots (or (:bots old-battle)
                        (fx/sub-val context get-in [:by-server server-key :battle :bots]))
        players (battle-players-and-bots
                  {:users (fx/sub-val context get-in [:by-server server-key :users])
                   :battle
                   {:bots battle-bots
                    :users battle-users}})
        username (fx/sub-val context get-in [:by-server server-key :username])
        my-player (->> players
                       (filter (comp #{username} :username))
                       first)
        team-counts (->> players
                         (filter (comp :mode :battle-status))
                         (group-by (comp :ally :battle-status))
                         (sort-by first)
                         (map (comp count second)))
        team-skills (->> players
                         (filter (comp :mode :battle-status))
                         (group-by (comp :ally :battle-status))
                         (sort-by first)
                         (map (fn [[_ally players]]
                                (reduce
                                  (fnil + 0 0)
                                  0
                                  (mapv
                                    (fn [{:keys [username]}]
                                      (let [username-lc (when username (string/lower-case username))
                                            skill (some-> (get-in scripttags ["game" "players" username-lc "skill"])
                                                          u/parse-skill
                                                          u/round)]
                                        skill))
                                    players)))))
        players-table {:fx/type players-table
                       :server-key server-key
                       :v-box/vgrow :always
                       :players players}
        battle-layout (if (contains? (set battle-layouts) battle-layout)
                        battle-layout
                        (first battle-layouts))
        battle-layout-key (if (= "vertical" battle-layout) :battle-vertical :battle-horizontal)
        battle-layout-default-split (if (= "vertical" battle-layout) 0.35 0.6)
        show-vote-log (fx/sub-val context :show-vote-log)
        battle-buttons (if-not old-battle
                         {:fx/type battle-buttons
                          :server-key server-key
                          :my-player my-player
                          :team-counts team-counts
                          :team-skills team-skills}
                         {:fx/type :pane})
        battle-chat {:fx/type :h-box
                     :children
                     [
                      {:fx/type fx.channel/channel-view
                       :h-box/hgrow :always
                       :channel-name (fx/sub-ctx context skylobby.fx/battle-channel-sub server-key battle-id)
                       :disable (boolean old-battle)
                       :hide-users true
                       :server-key server-key
                       :usernames (keys (or battle-users {}))}]}
        battle-tabs
        (if pop-out-chat
          {:fx/type battle-tabs
           :server-key server-key}
          (if show-vote-log
            {:fx/type :split-pane
             :orientation :vertical
             :divider-positions [0.9]
             :items
             [
              {:fx/type battle-tabs
               :server-key server-key}
              {:fx/type battle-votes
               :server-key server-key}]}
            {:fx/type :v-box
             :children
             [
              {:fx/type battle-tabs
               :v-box/vgrow :always
               :server-key server-key}
              {:fx/type battle-votes
               :server-key server-key}]}))
        resources-buttons {:fx/type :h-box
                           :style {:-fx-font-size 16}
                           :alignment :center-left
                           :children
                           [
                            {:fx/type :label
                             :text " Resources: "}
                            {:fx/type :button
                             :text "Import"
                             :on-action {:event/type :spring-lobby/toggle-window
                                         :windows-as-tabs (fx/sub-val context :windows-as-tabs)
                                         :key :show-importer}
                             :graphic
                             {:fx/type font-icon/lifecycle
                              :icon-literal (str "mdi-file-import:16:white")}}
                            {:fx/type :button
                             :text "HTTP"
                             :on-action {:event/type :spring-lobby/toggle-window
                                         :windows-as-tabs (fx/sub-val context :windows-as-tabs)
                                         :key :show-downloader}
                             :graphic
                             {:fx/type font-icon/lifecycle
                              :icon-literal (str "mdi-download:16:white")}}
                            {:fx/type :button
                             :text "Rapid"
                             :on-action {:event/type :spring-lobby/toggle-window
                                         :windows-as-tabs (fx/sub-val context :windows-as-tabs)
                                         :key :show-rapid-downloader}
                             :graphic
                             {:fx/type font-icon/lifecycle
                              :icon-literal (str "mdi-download:16:white")}}]}
        singleplayer (= :local server-key)
        direct-connect #p (= :direct server-key)
        am-host #p (fx/sub-ctx context sub/am-host server-key)
        resources-pane (if (or singleplayer
                               (and direct-connect
                                    am-host))
                         {:fx/type :v-box
                          :style (if singleplayer
                                   {:-fx-font-size 20}
                                   {:-fx-font-size 18
                                    :-fx-pref-width 660})
                          :children
                          [
                           {:fx/type engines-view
                            :engine-version engine-version
                            :on-value-changed {:event/type :spring-lobby/singleplayer-engine-changed
                                               :battle-id battle-id
                                               :server-key server-key
                                               :spring-root spring-root}
                            :spring-isolation-dir spring-root}
                           (if (seq engine-details)
                             {:fx/type mods-view
                              :engine-file engine-file
                              :mod-name mod-name
                              :on-value-changed {:event/type :spring-lobby/singleplayer-mod-changed
                                                 :battle-id battle-id
                                                 :server-key server-key
                                                 :spring-root spring-root}
                              :spring-isolation-dir spring-root}
                             {:fx/type :label
                              :text " Game: Get an engine first"})
                           {:fx/type maps-view
                            :map-name map-name
                            :on-value-changed {:event/type :spring-lobby/singleplayer-map-changed
                                               :battle-id battle-id
                                               :server-key server-key
                                               :spring-root spring-root}
                            :spring-isolation-dir spring-root}
                           {:fx/type :pane
                            :style {:-fx-pref-height 8}}
                           resources-buttons]}
                         {:fx/type :scroll-pane
                          :fit-to-width true
                          :hbar-policy :never
                          :content
                          {:fx/type :flow-pane
                           :vgap 5
                           :hgap 5
                           :padding 5
                           :children
                           (if singleplayer
                             (concat
                               [{:fx/type :h-box
                                 :alignment :center-left
                                 :children
                                 [{:fx/type engines-view
                                   :engine-version engine-version
                                   :spring-isolation-dir spring-root
                                   :suggest true
                                   :on-value-changed {:event/type :spring-lobby/assoc-in
                                                      :path [:by-server :local :battles :singleplayer :battle-version]}}]}]
                               [{:fx/type mods-view
                                 :mod-name mod-name
                                 :spring-isolation-dir spring-root
                                 :suggest true
                                 :on-value-changed {:event/type :spring-lobby/assoc-in
                                                    :path [:by-server :local :battles :singleplayer :battle-modname]}}]
                               [{:fx/type :h-box
                                 :alignment :center-left
                                 :children
                                 [{:fx/type maps-view
                                   :map-name map-name
                                   :spring-isolation-dir spring-root
                                   :suggest true
                                   :on-value-changed {:event/type :spring-lobby/assoc-in
                                                      :path [:by-server :local :battles :singleplayer :battle-map]}}]}])
                             (concat
                               [
                                {:fx/type engine-sync-pane
                                 :engine-version engine-version
                                 :spring-isolation-dir spring-root}
                                {:fx/type mod-sync-pane
                                 :engine-version engine-version
                                 :mod-name mod-name
                                 :spring-isolation-dir spring-root}]
                               (map
                                 (fn [{:keys [mod-name]}]
                                   {:fx/type mod-sync-pane
                                    :dependency true
                                    :engine-version engine-version
                                    :mod-name mod-name
                                    :spring-isolation-dir spring-root})
                                 mod-dependencies)
                               [{:fx/type map-sync-pane
                                 :map-name map-name
                                 :spring-isolation-dir spring-root}
                                resources-buttons
                                {:fx/type :h-box
                                 :alignment :center-left
                                 :children
                                 [{:fx/type :check-box
                                   :selected (boolean (fx/sub-val context :auto-get-resources))
                                   :on-selected-changed {:event/type :spring-lobby/assoc
                                                         :key :auto-get-resources}}
                                  {:fx/type :label
                                   :text " Auto import or download resources"}]}
                                {:fx/type :h-box
                                 :alignment :center-left
                                 :children
                                 [{:fx/type :label
                                   :text "Force sync check: "}
                                  {:fx/type sync-button
                                   :server-key server-key}]}]))}})
        resources-pane (if-not old-battle
                         resources-pane
                         {:fx/type :pane})]
    {:fx/type :v-box
     :children
     [
      {:fx/type :h-box
       :alignment :center-left
       :style {:-fx-font-size 16
               :-fx-padding 8}
       :children
       (concat
         (when battle-id
           (concat
             [{:fx/type :button
               :text "Leave Battle"
               :on-action {:event/type :spring-lobby/leave-battle
                           :client-data (fx/sub-val context get-in [:by-server server-key :client-data])
                           :server-key server-key}}
              {:fx/type :pane
               :h-box/margin 4}]
             (when-not singleplayer
               [(if (fx/sub-val context :pop-out-battle)
                  {:fx/type :button
                   :text "Pop In Battle "
                   :graphic
                   {:fx/type font-icon/lifecycle
                    :icon-literal "mdi-window-maximize:16:white"}
                   :on-action {:event/type :spring-lobby/dissoc
                               :key :pop-out-battle}}
                  {:fx/type :button
                   :text "Pop Out Battle "
                   :graphic
                   {:fx/type font-icon/lifecycle
                    :icon-literal "mdi-open-in-new:16:white"}
                   :on-action {:event/type :spring-lobby/assoc
                               :key :pop-out-battle
                               :value true}})])
             [{:fx/type :pane
               :h-box/margin 4}]
             (when-not singleplayer
               [(if pop-out-chat
                  {:fx/type :button
                   :text "Pop In Chat "
                   :graphic
                   {:fx/type font-icon/lifecycle
                    :icon-literal "mdi-window-maximize:16:white"}
                   :on-action {:event/type :spring-lobby/dissoc
                               :key :pop-out-chat}}
                  {:fx/type :button
                   :text "Pop Out Chat "
                   :graphic
                   {:fx/type font-icon/lifecycle
                    :icon-literal "mdi-open-in-new:16:white"}
                   :on-action {:event/type :spring-lobby/assoc
                               :key :pop-out-chat
                               :value true}})]))))}
      {:fx/type :h-box
       :v-box/vgrow :always
       :style {:-fx-font-size 15}
       :alignment :top-left
       :children
       (if singleplayer
         [
          {:fx/type :v-box
           :h-box/hgrow :always
           :children
           [players-table
            resources-pane
            battle-buttons]}
          battle-tabs]
         (concat
           [{:fx/type ext-recreate-on-key-changed
             :h-box/hgrow :always
             :key (str battle-layout)
             :desc
             {:fx/type fx/ext-on-instance-lifecycle
              :on-created (fn [^javafx.scene.control.SplitPane node]
                            (let [dividers (.getDividers node)]
                              (when-let [^javafx.scene.control.SplitPane$Divider divider (first dividers)]
                                (.addListener (.positionProperty divider)
                                  (reify javafx.beans.value.ChangeListener
                                    (changed [_this _observable _old-value new-value]
                                      (swap! skylobby.fx/divider-positions assoc battle-layout-key new-value)))))))
              :desc
              {:fx/type :split-pane
               :orientation (if (= "vertical" battle-layout) :horizontal :vertical)
               :divider-positions [(or (get divider-positions battle-layout-key)
                                       battle-layout-default-split)]
               :items
               (concat
                 [
                  {:fx/type :v-box
                   :children
                   (if (= "vertical" battle-layout)
                     (concat
                       [(assoc players-table :v-box/vgrow :always)]
                       (when (fx/sub-val context :battle-resource-details)
                         [resources-pane])
                       [battle-buttons])
                     [{:fx/type :h-box
                       :v-box/vgrow :always
                       :children
                       (concat
                         [(assoc players-table :h-box/hgrow :always)]
                         (when (fx/sub-val context :battle-resource-details)
                           [resources-pane]))}
                      battle-buttons])}]
                 (when-not pop-out-chat
                   [battle-chat]))}}}]
           [battle-tabs]))}]}))

(defn battle-view
  [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :battle-view
      (battle-view-impl state))))


(defn multi-battle-view
  [{:keys [battle]}]
  (if (< 1 (count battle))
    {:fx/type :tab-pane
     :style {:-fx-font-size 16}
     :tabs
     [{:fx/type :tab
       :on-close-request {:event/type :spring-lobby/leave-battle
                          :client-data (:client-data battle)}
       :graphic {:fx/type :label
                 :text "Multiplayer"}
       :content
       (merge
         {:fx/type battle-view}
         battle)}
      {:fx/type :tab
       :on-close-request {:event/type :spring-lobby/dissoc
                          :key :singleplayer-battle}
       :graphic {:fx/type :label
                 :text "Singleplayer"}
       :content
       (merge
         {:fx/type battle-view}
         battle)}]}
    (merge
      {:fx/type battle-view}
      battle)))
