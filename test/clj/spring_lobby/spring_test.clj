(ns spring-lobby.spring-test
  (:require
    [clojure.data]
    [clojure.pprint :refer [pprint]]
    [clojure.string :as string]
    [clojure.test :refer [deftest is testing]]
    [spring-lobby.spring :as spring]))


(set! *warn-on-reflection* true)


(declare battle battle-players expected-script-data expected-script-data-players
         expected-script-txt expected-script-txt-players)


(deftest startpostype-name
  (is (= "Fixed"
         (spring/startpostype-name 0)))
  (is (= "Choose before game"
         (spring/startpostype-name 3))))


(deftest normalize-team
  (is (= :team1
         (spring/normalize-team :team1)))
  (is (= :team1
         (spring/normalize-team :1)))
  (is (= :team1
         (spring/normalize-team "1")))
  (is (= :team1
         (spring/normalize-team "team1"))))


(deftest script-data-client
  (is (= {"game"
          {"hostip" "127.0.0.1"
           "hostport" 12345
           "ishost" 0
           "mypasswd" "secret-password"
           "myplayername" "skynet"}}
         (spring/script-data-client
           {:battle-ip "127.0.0.1"
            :battle-port 12345
            :script-password "secret-password"}
           {:game {"myplayername" "skynet"}}))))


(deftest script-data
  (testing "no players"
    (is (= expected-script-data
           (assoc-in
             (spring/script-data battle
               {:is-host true
                :game {"myplayername" "me"}})
             ["game" "hostip"] nil))))
  (testing "player and bot"
    (is (= expected-script-data-players
           (spring/script-data
             battle-players
             {:is-host true
              :game {"myplayername" "me"}
              :sides {0 "ARM" 1 "CORE"}}))))
  (testing "fix git game name"
    (is (= (assoc-in expected-script-data ["game" "gametype"] "Beyond All Reason git:f0cf2cb")
           (assoc-in
             (spring/script-data
               (assoc battle :battle-modname "Beyond All Reason git:f0cf2cb")
               {:is-host true
                :game {"myplayername" "me"}})
             ["game" "hostip"] nil)))))

(deftest script-test
  (is (= expected-script-txt
         (spring/script-txt
           (sort-by first expected-script-data))))
  (testing "with players"
    (let [expected expected-script-txt-players
          actual (spring/script-txt
                   (sort-by first expected-script-data-players))]
      (is (= expected actual))
      (when (not= expected actual)
        (println (str "expected:\n" expected))
        (println (str "actual:\n" actual))
        (let [diff (clojure.data/diff (string/split-lines expected) (string/split-lines actual))]
          (println "diff:")
          (pprint diff))))))


(def battle
  {:battle-modhash -1706632985
   :battle-version "104.0.1-1510-g89bb8e3 maintenance"
   :battle-map "Dworld Acidic"
   :battle-title "deth"
   :battle-modname "Balanced Annihilation V9.79.4"
   :battle-maphash -1611391257
   :battle-port 8452
   :battle-ip "127.0.0.1"})

(def expected-script-data
  {"game"
   {"gametype" "Balanced Annihilation V9.79.4"
    "mapname" "Dworld Acidic"
    "hostport" 8452
    "hostip" nil
    "ishost" 1
    "modoptions" {}
    "myplayername" "me"}})

(def battle-players
  {:battle-modhash -1
   :battle-version "103.0"
   :battle-map "Dworld Duo"
   :battle-title "deth"
   :battle-modname "Balanced Annihilation V10.24"
   :battle-maphash -1
   :battle-port 8452
   :battle-ip nil ;"192.168.1.6"
   :users
   {"skynet9001"
    {:battle-status
     {:id 0
      :ally 0
      :team-color 0
      :handicap 0
      :mode 1
      :side 0}
     :team-color 0}}
   :bots
   {"kekbot1"
    {:ai-name "KAIK"
     :ai-version "0.13"
     :owner "skynet9001"
     :team-color 1
     :battle-status
     {:id 1
      :ally 1
      :team-color 1
      :handicap 1
      :mode 1
      :side 1}}}})

(def expected-script-data-players
  {"game"
   {"gametype" "Balanced Annihilation V10.24"
    "mapname" "Dworld Duo"
    "hostport" 8452
    "ishost" 1
    "hostip" "0.0.0.0"
    "myplayername" "me"
    "modoptions" {}
    "team0"
    {"teamleader" 0
     "handicap" 0
     "allyteam" 0
     "rgbcolor" "0.0 0.0 0.0"
     "side" "ARM"}
    "team1"
    {"teamleader" 0
     "handicap" 1
     "allyteam" 1
     "rgbcolor" "0.00392156862745098 0.0 0.0"
     "side" "CORE"},
    "allyteam1" {"numallies" 0}
    "allyteam0" {"numallies" 0}
    "ai1"
    {"name" "kekbot1"
     "shortname" "KAIK"
     "version" "0.13"
     "host" 0
     "team" 1,
     "isfromdemo" 0
     "options" {}},
    "player0"
    {"name" "skynet9001",
     "team" 0,
     "isfromdemo" 0,
     "countrycode" nil
     "spectator" 0}}})

(def expected-script-txt
  "[game]
{
\tgametype = Balanced Annihilation V9.79.4;
\thostip = ;
\thostport = 8452;
\tishost = 1;
\tmapname = Dworld Acidic;
\t[modoptions]
\t{
\t}

\tmyplayername = me;
}

")

(def expected-script-txt-players
  "[game]
{
\t[ai1]
\t{
\t\thost = 0;
\t\tisfromdemo = 0;
\t\tname = kekbot1;
\t\t[options]
\t\t{
\t\t}

\t\tshortname = KAIK;
\t\tteam = 1;
\t\tversion = 0.13;
\t}

\t[allyteam0]
\t{
\t\tnumallies = 0;
\t}

\t[allyteam1]
\t{
\t\tnumallies = 0;
\t}

\tgametype = Balanced Annihilation V10.24;
\thostip = 0.0.0.0;
\thostport = 8452;
\tishost = 1;
\tmapname = Dworld Duo;
\t[modoptions]
\t{
\t}

\tmyplayername = me;
\t[player0]
\t{
\t\tcountrycode = ;
\t\tisfromdemo = 0;
\t\tname = skynet9001;
\t\tspectator = 0;
\t\tteam = 0;
\t}

\t[team0]
\t{
\t\tallyteam = 0;
\t\thandicap = 0;
\t\trgbcolor = 0.0 0.0 0.0;
\t\tside = ARM;
\t\tteamleader = 0;
\t}

\t[team1]
\t{
\t\tallyteam = 1;
\t\thandicap = 1;
\t\trgbcolor = 0.00392156862745098 0.0 0.0;
\t\tside = CORE;
\t\tteamleader = 0;
\t}

}

")
