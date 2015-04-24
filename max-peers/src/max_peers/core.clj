(ns max-peers.core
  (:require [clojure.core.async :refer [chan >!! <!! close!]]
            [onyx.peer.task-lifecycle-extensions :as l-ext]
            [onyx.plugin.core-async :refer [take-segments!]]
            [onyx.api]))

(def workflow
  [[:in :add]
   [:add :out]])

(defn my-adder [{:keys [n] :as segment}]
  (assoc segment :n (+ n 35)))

(def capacity 1000)

(def input-chan (chan capacity))

(def output-chan (chan capacity))

(defmethod l-ext/inject-lifecycle-resources :in
  [_ _] {:core.async/chan input-chan})

(defmethod l-ext/inject-lifecycle-resources :out
  [_ _] {:core.async/chan output-chan})

(def batch-size 10)

(def catalog
  [{:onyx/name :in
    :onyx/ident :core.async/read-from-chan
    :onyx/type :input
    :onyx/medium :core.async
    :onyx/consumption :concurrent
    :onyx/batch-size batch-size
    :onyx/doc "Reads segments from a core.async channel"}

   {:onyx/name :add
    :onyx/ident :parameterized.core/my-adder
    :onyx/fn :max-peers.core/my-adder
    :onyx/type :function
    :onyx/consumption :concurrent
    :onyx/max-peers 1
    :onyx/batch-size batch-size}

   {:onyx/name :out
    :onyx/ident :core.async/write-to-chan
    :onyx/type :output
    :onyx/medium :core.async
    :onyx/consumption :concurrent
    :onyx/batch-size batch-size
    :onyx/doc "Writes segments to a core.async channel"}])

;; Don't write any segments to core.async, forcing the job to stall
;; and do nothing.

(def id (java.util.UUID/randomUUID))

(def scheduler :onyx.job-scheduler/balanced)

(def env-config
  {:hornetq/mode :vm
   :hornetq.server/type :vm
   :hornetq/server? true
   :zookeeper/address "127.0.0.1:2186"
   :zookeeper/server? true
   :zookeeper.server/port 2186
   :onyx/id id
   :onyx.peer/job-scheduler scheduler})

(def peer-config
  {:hornetq/mode :vm
   :zookeeper/address "127.0.0.1:2186"
   :onyx/id id
   :onyx.peer/job-scheduler scheduler})

(def env (onyx.api/start-env env-config))

(def v-peers (onyx.api/start-peers n-peers peer-group))

(def job-id
  (onyx.api/submit-job
   peer-config
   {:catalog catalog :workflow workflow
    :task-scheduler :onyx.task-scheduler/balanced}))

;;; Inspect the logs to see that only one peer was assigned
;;; the :add task. Job will be killed in 10 seconds.

(Thread/sleep 10000)

(onyx.api/kill-job peer-config job-id)

(doseq [v-peer v-peers]
  (onyx.api/shutdown-peer v-peer))

(onyx.api/shutdown-env env)

