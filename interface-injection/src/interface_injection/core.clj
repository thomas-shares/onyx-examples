(ns interface-injection.core
  (:require [clojure.core.async :refer [chan >!! <!! close!]]
            [onyx.plugin.core-async :refer [take-segments!]]
            [onyx.api]))

(defprotocol ITimesTwo
  (times-two [this n]))

(deftype DevelopmentMultiplication []
  ITimesTwo
  (times-two [this n] (* n 2)))

(deftype ProductionMultiplication []
  ITimesTwo
  ;; Because who doesn't ship bugs in prod?
  (times-two [this n] (* n 3)))

(def workflow
  [[:in :add]
   [:add :out]])

(defn my-adder [t {:keys [n] :as segment}]
  (assoc segment :n (times-two t n)))

(def capacity 1000)

(def input-chan (chan capacity))
(def input-buffer (atom {}))

(def output-chan (chan capacity))

(def batch-size 10)

(def catalog
  [{:onyx/name :in
    :onyx/plugin :onyx.plugin.core-async/input
    :onyx/type :input
    :onyx/medium :core.async
    :onyx/max-peers 1
    :onyx/batch-size batch-size
    :onyx/doc "Reads segments from a core.async channel"}

   {:onyx/name :add
    :onyx/fn :interface-injection.core/my-adder
    :onyx/type :function
    :onyx/batch-size batch-size
    :interface-injection.core/k 42}

   {:onyx/name :out
    :onyx/plugin :onyx.plugin.core-async/output
    :onyx/type :output
    :onyx/medium :core.async
    :onyx/max-peers 1
    :onyx/batch-size batch-size
    :onyx/doc "Writes segments to a core.async channel"}])

(def input-segments
  [{:n 0}
   {:n 1}
   {:n 2}
   {:n 3}
   {:n 4}
   {:n 5}])

(doseq [segment input-segments]
  (>!! input-chan segment))

(close! input-chan)

(def id (java.util.UUID/randomUUID))

(def env-config
  {:zookeeper/address "127.0.0.1:2188"
   :zookeeper/server? true
   :zookeeper.server/port 2188
   :onyx/tenancy-id id})

(def peer-config
  {:zookeeper/address "127.0.0.1:2188"
   :onyx/tenancy-id id
   :onyx.peer/job-scheduler :onyx.job-scheduler/balanced
   :onyx.messaging/impl :aeron
   :onyx.messaging/peer-port 40200
   :onyx.messaging/bind-addr "localhost"
   :onyx.peer/fn-params {:add [(DevelopmentMultiplication.)]}})

(def env (onyx.api/start-env env-config))

(def peer-group (onyx.api/start-peer-group peer-config))

(def n-peers (count (set (mapcat identity workflow))))

(def v-peers (onyx.api/start-peers n-peers peer-group))

(defn inject-in-ch [event lifecycle]
  {:core.async/buffer input-buffer
   :core.async/chan input-chan})

(defn inject-out-ch [event lifecycle]
  {:core.async/chan output-chan})

(def in-calls
  {:lifecycle/before-task-start inject-in-ch})

(def out-calls
  {:lifecycle/before-task-start inject-out-ch})

(def lifecycles
  [{:lifecycle/task :in
    :lifecycle/calls :interface-injection.core/in-calls}
   {:lifecycle/task :in
    :lifecycle/calls :onyx.plugin.core-async/reader-calls}
   {:lifecycle/task :out
    :lifecycle/calls :interface-injection.core/out-calls}
   {:lifecycle/task :out
    :lifecycle/calls :onyx.plugin.core-async/writer-calls}])

(def submission
  (onyx.api/submit-job peer-config 
                       {:catalog catalog 
                        :workflow workflow 
                        :lifecycles lifecycles
                        :task-scheduler :onyx.task-scheduler/balanced}))

(onyx.api/await-job-completion peer-config (:job-id submission))

(def results (take-segments! output-chan 50))

(clojure.pprint/pprint results)

(doseq [v-peer v-peers]
  (onyx.api/shutdown-peer v-peer))

(onyx.api/shutdown-peer-group peer-group)

(onyx.api/shutdown-env env)
