(ns skylobby.fx.channel
  (:require
    [cljfx.ext.node :as fx.ext.node]
    [clojure.string :as string]
    [skylobby.fx :refer [monospace-font-family]]
    [skylobby.fx.ext :refer [with-scroll-text-prop with-scroll-text-flow-prop]]
    [spring-lobby.fx.font-icon :as font-icon]
    [spring-lobby.util :as u]
    [taoensso.tufte :as tufte]))


(def default-font-size 18)

(defn font-size-or-default [font-size]
  (int (or (when (number? font-size) font-size)
           default-font-size)))

(def irc-colors
  {"00" "rgb(255,255,255)"
   "01" "rgb(255,255,255)" ; use white for black since dark theme "rgb(0,0,0)"
   "02" "rgb(0,0,127)"
   "03" "rgb(0,147,0)"
   "04" "rgb(255,0,0)"
   "05" "rgb(127,0,0)"
   "06" "rgb(156,0,156)"
   "07" "rgb(252,127,0)"
   "08" "rgb(255,255,0)"
   "09" "rgb(0,252,0)"
   "10" "rgb(0,147,147)"
   "11" "rgb(0,255,255)"
   "12" "rgb(0,0,252)"
   "13" "rgb(255,0,255)"
   "14" "rgb(127,127,127)"
   "15" "rgb(210,210,210)"})

(defn text-style [font-size]
  {:-fx-font-family monospace-font-family
   :-fx-font-size (font-size-or-default font-size)})


(defn channel-texts [messages]
  (let [last-message-index (dec (count messages))]
    (->> messages
         (map-indexed vector)
         (mapcat
           (fn [[i {:keys [ex text timestamp username]}]]
             (concat
               [{:fx/type :text
                 :text (str "[" (u/format-hours timestamp) "] ")
                 :style-class ["text" "skylobby-chat-time"]}
                {:fx/type :text
                 :text
                 (str
                   (if ex
                     (str "* " username " " text)
                     (str username ": ")))
                 :style-class ["text" (str "skylobby-chat-username" (when ex "-ex"))]}]
               (when-not ex
                 (map
                   (fn [[_all _ _irc-color-code text-segment]]
                     {:fx/type :text
                      :text (str text-segment)
                      :style-class ["text" "skylobby-chat-message"]})
                   (re-seq #"([\u0003](\d\d))?([^\u0003]+)" text)))
               (when-not (= i last-message-index)
                 [{:fx/type :text
                   :text "\n"}])))))))

(defn- channel-view-history-impl
  [{:keys [chat-auto-scroll channel-name chat-font-size messages select-mode server-key]}]
  (let [messages (reverse messages)]
    (if select-mode
      (let [text (->> messages
                      (map
                        (fn [{:keys [ex text timestamp username]}]
                          (str
                            "[" (u/format-hours timestamp) "] "
                            (if ex
                              (str "* " username " " text)
                              (str username ": " text)))))
                      (string/join "\n"))]
        {:fx/type with-scroll-text-prop
         :props {:scroll-text [text chat-auto-scroll]}
         :desc
         {:fx/type :text-area
          :editable false
          :wrap-text true
          :style (text-style chat-font-size)
          :context-menu
          {:fx/type :context-menu
           :items
           [{:fx/type :menu-item
             :text "Color mode"
             :on-action {:event/type :spring-lobby/assoc-in
                         :path [:by-server server-key :channels channel-name :select-mode]
                         :value false}}]}}})
      (let [texts (channel-texts messages)]
        {:fx/type with-scroll-text-flow-prop
         :props {:auto-scroll [texts chat-auto-scroll]}
         :desc
         {:fx/type :scroll-pane
          :style {:-fx-min-width 200
                  :-fx-pref-width 200}
          :fit-to-width true
          :on-scroll {:event/type :spring-lobby/enable-auto-scroll-if-at-bottom}
          :context-menu
          {:fx/type :context-menu
           :items
           [{:fx/type :menu-item
             :text "Select mode"
             :on-action {:event/type :spring-lobby/assoc-in
                         :path [:by-server server-key :channels channel-name :select-mode]
                         :value true}}]}
          :content
          {:fx/type :text-flow
           :on-scroll {:event/type :spring-lobby/disable-auto-scroll}
           :style (text-style chat-font-size)
           :children texts}}}))))

(defn channel-view-history
  [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :channel-view-history
      (channel-view-history-impl state))))

(defn- channel-view-input [{:keys [channel-name chat-auto-scroll client-data message-draft server-key]}]
  {:fx/type :h-box
   :children
   [{:fx/type :button
     :text "Send"
     :on-action {:event/type :spring-lobby/send-message
                 :channel-name channel-name
                 :client-data client-data
                 :message message-draft
                 :server-key server-key}}
    {:fx/type :text-field
     :id "channel-text-field"
     :h-box/hgrow :always
     :text (str message-draft)
     :on-text-changed {:event/type :spring-lobby/assoc-in
                       :path [:by-server server-key :message-drafts channel-name]}
     :on-action {:event/type :spring-lobby/send-message
                 :channel-name channel-name
                 :client-data client-data
                 :message message-draft
                 :server-key server-key}
     :on-key-pressed {:event/type :spring-lobby/on-channel-key-pressed
                      :channel-name channel-name
                      :server-key server-key}}
    {:fx/type fx.ext.node/with-tooltip-props
     :props
     {:tooltip
      {:fx/type :tooltip
       :show-delay [10 :ms]
       :text "Auto scroll"}}
     :desc
     {:fx/type :h-box
      :alignment :center-left
      :children
      [
       {:fx/type font-icon/lifecycle
        :icon-literal "mdi-autorenew:20:white"}
       {:fx/type :check-box
        :selected (boolean chat-auto-scroll)
        :on-selected-changed {:event/type :spring-lobby/assoc
                              :key :chat-auto-scroll}}]}}]})

(defn- channel-view-users [{:keys [users]}]
  {:fx/type :table-view
   :column-resize-policy :constrained ; TODO auto resize
   :items (->> users
               keys
               (sort String/CASE_INSENSITIVE_ORDER)
               vec)
   :row-factory
   {:fx/cell-type :table-row
    :describe (fn [i]
                {
                 :context-menu
                 {:fx/type :context-menu
                  :items
                  [
                   {:fx/type :menu-item
                    :text "Message"
                    :on-action {:event/type :spring-lobby/join-direct-message
                                :username i}}]}})}
   :columns
   [{:fx/type :table-column
     :text "Username"
     :cell-value-factory identity
     :cell-factory
     {:fx/cell-type :table-cell
      :describe
      (fn [i]
        {:text (-> i str)
         :style-class ["text" "skylobby-chat-user-list"]})}}]})

(def channel-state-keys
  [:chat-font-size])

(defn channel-view-impl
  [{:keys [channel-name channels hide-users]
    :as state}]
  (let [{:keys [users] :as channel-data} (get channels channel-name)]
    {:fx/type :h-box
     :children
     (concat
       [{:fx/type :v-box
         :h-box/hgrow :always
         :style {:-fx-font-size 16}
         :children
         [(merge
            {:fx/type channel-view-history
             :v-box/vgrow :always}
            state
            channel-data)
          (merge
            {:fx/type channel-view-input}
            state)]}]
       (when (and (not hide-users)
                  channel-name
                  (not (string/starts-with? channel-name "@")))
         [{:fx/type channel-view-users
           :users users}]))}))


(defn channel-view
  [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :channel-view
      (channel-view-impl state))))