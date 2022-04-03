(ns skylobby.task
  (:require
    [taoensso.timbre :as log]))


(set! *warn-on-reflection* true)


(def index-tasks
  #{:spring-lobby/refresh-engines
    :spring-lobby/refresh-mods
    :spring-lobby/refresh-maps})

(def resource-tasks
  #{
    :spring-lobby/map-details
    :spring-lobby/mod-details
    :spring-lobby/update-cached-minimaps})

(def download-tasks
  #{
    :spring-lobby/download-and-extract
    :spring-lobby/download-springfiles
    :spring-lobby/extract-7z
    :spring-lobby/import
    :spring-lobby/http-downloadable})

(def rapid-tasks
  #{
    :spring-lobby/rapid-download
    :spring-lobby/update-rapid
    :spring-lobby/update-rapid-packages
    :spring-lobby/delete-corrupt-rapid})

(def task-kinds
  [:spring-lobby/index-task
   :spring-lobby/resource-task
   :spring-lobby/download-task
   :spring-lobby/rapid-task
   :spring-lobby/other-task])

(defn task-kind [{:spring-lobby/keys [task-type]}]
  (cond
    (contains? index-tasks task-type) :spring-lobby/index-task
    (contains? resource-tasks task-type) :spring-lobby/resource-task
    (contains? download-tasks task-type) :spring-lobby/download-task
    (contains? rapid-tasks task-type) :spring-lobby/rapid-task
    :else :spring-lobby/other-task))

(defn add-task-state [state task]
  (if task
    (let [task-kind (task-kind task)]
      (log/info "Adding task" (pr-str task) "to" task-kind)
      (update-in state [:tasks-by-kind task-kind]
        (fn [tasks]
          (set (conj tasks task)))))
    (log/warn "Attempt to add nil task" task)))

(defn add-task! [state-atom task]
  (swap! state-atom add-task-state task))

(defn add-multiple-tasks [tasks-by-kind new-tasks]
  (reduce-kv
    (fn [m k new-tasks]
      (update m k (fn [existing]
                    (set (concat new-tasks existing)))))
    tasks-by-kind
    (group-by task-kind new-tasks)))

(defn add-tasks! [state-atom new-tasks]
  (log/info "Adding tasks" (pr-str new-tasks))
  (swap! state-atom update :tasks-by-kind add-multiple-tasks new-tasks))


(defn all-tasks [{:keys [current-tasks tasks-by-kind]}]
  (->> tasks-by-kind
       (mapcat second)
       (concat (vals current-tasks))
       (filter some?)
       doall))
