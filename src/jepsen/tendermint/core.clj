(ns jepsen.tendermint.core
  (:refer-clojure :exclude [test])
  (:require [clojure.tools.logging :refer :all]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]
            [knossos.model :as model]
            [slingshot.slingshot :refer [try+]]
            [jepsen [checker :as checker]
             [cli :as cli]
             [client :as client]
             [control :as c]
             [db :as db]
             [generator :as gen]
             [independent :as independent]
             [nemesis :as nemesis]
             [tests :as tests]
             [util :as util :refer [timeout with-retry map-vals]]]
            [jepsen.checker.timeline :as timeline]
            [jepsen.nemesis.time :as nt]
            [jepsen.control.util :as cu]
            [jepsen.os.debian :as debian]
            [cheshire.core :as json]
            [jepsen.tendermint [client :as tc]
                               [db :as td]
                               [util :refer [base-dir]]
                               [validator :as tv]]
            ))

(defn r   [_ _] {:type :invoke, :f :read,  :value nil})
(defn w   [_ _] {:type :invoke, :f :write, :value (rand-int 10)})
(defn cas [_ _] {:type :invoke, :f :cas,   :value [(rand-int 10) (rand-int 10)]})

(defn cas-register-client
  ([]
   (cas-register-client nil))
  ([node]
   (reify client/Client
     (setup! [_ test node]
       (cas-register-client node))

     (invoke! [_ test op]
       (let [[k v] (:value op)
             crash (if (= (:f op) :read)
                     :fail
                     :info)]
         (try+
           (case (:f op)
             :read  (assoc op
                           :type :ok
                           :value (independent/tuple k (tc/read node k)))
             :write (do (tc/write! node k v)
                        (assoc op :type :ok))
             :cas   (let [[v v'] v]
                      (tc/cas! node k v v')
                      (assoc op :type :ok)))

           (catch [:type :unauthorized] e
             (assoc op :type :fail, :error :precondition-failed))

           (catch [:type :base-unknown-address] e
             (assoc op :type :fail, :error :not-found))

           (catch org.apache.http.NoHttpResponseException e
             (assoc op :type crash, :error :no-http-response))

           (catch java.net.ConnectException e
             (condp re-find (.getMessage e)
               #"Connection refused"
               (assoc op :type :fail, :error :connection-refused)

               (assoc op :type crash, :error [:connect-exception
                                              (.getMessage e)])))

           (catch java.net.SocketTimeoutException e
             (assoc op :type crash, :error :timeout)))))

     (teardown! [_ test]))))


(defn set-client
  ([]
   (set-client nil))
  ([node]
   (reify client/Client
     (setup! [_ test node]
       (set-client node))

     (invoke! [_ test op]
       (let [[k v] (:value op)
             crash (if (= (:f op) :read)
                     :fail
                     :info)]
         (try+
           (case (:f op)
             :init (with-retry [tries 0]
                     (tc/write! node k [])
                     (assoc op :type :ok)
                     (catch Exception e
                       (if (<= 10 tries)
                         (throw e)
                         (do (info "Couldn't initialize key" k ":" (.getMessage e) "- retrying")
                             (Thread/sleep (* 50 (Math/pow 2 tries)))
                             (retry (inc tries))))))
             :add (let [s (or (vec (tc/read node k)) [])
                        s' (conj s v)]
                    (tc/cas! node k s s')
                    (assoc op :type :ok))
             :read (assoc op
                          :type :ok
                          :value (independent/tuple
                                   k
                                   (into (sorted-set) (tc/read node k)))))

           (catch [:type :unauthorized] e
             (assoc op :type :fail, :error :precondition-failed))

           (catch [:type :base-unknown-address] e
             (assoc op :type :fail, :error :not-found))

           (catch org.apache.http.NoHttpResponseException e
             (assoc op :type crash, :error :no-http-response))

           (catch java.net.ConnectException e
             (condp re-find (.getMessage e)
               #"Connection refused"
               (assoc op :type :fail, :error :connection-refused)

               (assoc op :type crash, :error [:connect-exception
                                              (.getMessage e)])))

           (catch java.net.SocketTimeoutException e
             (assoc op :type crash, :error :timeout)))))

     (teardown! [_ test]))))

(defn dup-groups
  "Takes a test with a :dup-validators map of nodes to the nodes they imitate,
  and turns that into a collection of collections of nodes, each of which is
  several nodes pretending to be the same node. Returns a map of :groups, which
  are the aforementioned groups, :singles, those groups with only 1 node, and
  :dups, with more than one."
  [test]
  (let [dv (:dup-validators test)
        groups (->> (:nodes test)
                    (reduce (fn [index node]
                              (let [orig (get dv node node)
                                    coll (get index orig #{})]
                                (assoc index orig (conj coll node))))
                            {})
                    vals)]
    {:groups  groups
     :singles (filter #(= 1 (count %)) groups)
     :dups    (filter #(< 1 (count %)) groups)}))

(defn peekaboo-dup-validators-grudge
  "Takes a test. Returns a function which takes a collection of nodes from that
  test, and constructs a network partition (a grudge) which isolates some dups
  completely, and leaves one connected to the majority component."
  [test]
  (let [{:keys [groups singles dups]} (dup-groups test)]
    (fn [nodes]
      ; Pick one random node from every group of dups to participate in the
      ; main component, and compute the remaining complement for each dup
      ; group.
      (let [chosen-ones (map (comp hash-set rand-nth vec) dups)
            exiles      (map remove chosen-ones dups)]
        (nemesis/complete-grudge
          (cons ; Main group
                (set (concat (apply concat singles)
                             (apply concat chosen-ones)))
                ; Exiles
                exiles))))))

(defn split-dup-validators-grudge
  "Takes a test. Returns a function which takes a collection of nodes from that
  test, and constructs a network partition (a grudge) which splits the network
  into n disjoint components, each having a single duplicate validator and an
  equal share of the remaining nodes."
  [test]
  (let [{:keys [groups singles dups]} (dup-groups test)]
    (fn [nodes]
      (let [n (reduce max (map count dups))]
        (->> groups
             shuffle
             (map shuffle)
             (apply concat)
             (reduce (fn [[components i] node]
                       [(update components (mod i n) conj node)
                        (inc i)])
                     [[] 0])
             first
             nemesis/complete-grudge)))))

(defn crash-truncate-nemesis
  "A nemesis which kills tendermint, kills merkleeyes, truncates the merkleeyes
  log, and restarts the process, on up to `fraction` of the test's nodes."
  [test fraction]
  (let [faulty-nodes (take (Math/floor (* fraction (count (:nodes test))))
                           (shuffle (:nodes test)))]
    (reify client/Client
      (setup! [this test _] this)

      (invoke! [this test op]
        (assert (= (:f op) :crash))
        (c/on-nodes test faulty-nodes
                    (fn [test node]
                      (td/stop-tendermint! test node)
                      (td/stop-merkleeyes! test node)
                      (c/su
                        (c/exec :truncate :-c :-s
                                (str "-" (rand-int 1048576))
                                (str base-dir "/jepsen/jepsen.db/000001.log")))
                      (td/start-merkleeyes! test node)
                      (td/start-tendermint! test node)))
        op)

      (teardown! [this test]
        ; Ensure processes start back up by the end
        (c/on-nodes test faulty-nodes td/start-merkleeyes!)
        (c/on-nodes test faulty-nodes td/start-tendermint!)))))

(defn crash-nemesis
  "A nemesis which kills merkleeyes and tendermint on all nodes."
  []
  (nemesis/node-start-stopper identity td/stop! td/start!))

(defn changing-validators-nemesis
  "A nemesis which takes {:nodes [active-node-set], :transition {...}} values
  and applies those transitions to the cluster."
  []
  (reify client/Client
    (setup! [this test _] this)

    (invoke! [this test op]
      (assert (= :transition (:f op)))
      (let [t (:value op)]
        (case (:type t)
          :add
          (tc/validator-set-cas!
            (rand-nth (:nodes test))
            (:version t)
            (:data (:pub_key (:validator t)))
            (:votes (:validator t)))

          :remove
          (tc/validator-set-cas!
            (rand-nth (:nodes test))
            (:version t)
            (:data (:pub_key t))
            0)

          :alter-votes
          (tc/validator-set-cas!
            (rand-nth (:nodes test))
            (:version t)
            (:data (:pub_key t))
            (:votes t))

          :create
          (c/on-nodes test (list (:node t))
                      (fn create [test node]
                        (td/write-validator! (:validator t))
                        (td/start! test node)))

          :destroy
          (c/on-nodes test (list (:node t))
                      (fn destroy [test node]
                        (td/stop! test node)
                        (td/reset-node! test node)))

          :stop nil)

        ; After we've executed an operation, we need to update our test state to
        ; reflect the new state of things.
        (swap! (:validator-config test) #(tv/step % t)))

      op)

    (teardown! [this test])))

(defn nemesis
  "The generator and nemesis for each nemesis profile"
  [test]
  (case (:nemesis test)
    :changing-validators {:nemesis   (changing-validators-nemesis)
                          :generator (gen/stagger 10 (tv/generator))}

    :peekaboo-dup-validators {:nemesis (nemesis/partitioner
                                         (peekaboo-dup-validators-grudge test))
                              :generator (gen/start-stop 0 5)}

    :split-dup-validators {:nemesis (nemesis/partitioner
                                      (split-dup-validators-grudge test))
                           :generator (gen/once {:type :info, :f :start})}

    :half-partitions {:nemesis   (nemesis/partition-random-halves)
                      :generator (gen/start-stop 5 30)}

    :ring-partitions {:nemesis (nemesis/partition-majorities-ring)
                      :generator (gen/start-stop 5 30)}

    :single-partitions {:nemesis (nemesis/partition-random-node)
                        :generator (gen/start-stop 5 30)}

    :clocks     {:nemesis   (nt/clock-nemesis)
                 :generator (gen/stagger 5 (nt/clock-gen))}

    :crash      {:nemesis (crash-nemesis)
                 :generator (gen/start-stop 15 0)}

    :crash-truncate {:nemesis (nemesis/compose
                                {#{:crash} (crash-truncate-nemesis test 1/3)
                                 #{:stop}  nemesis/noop})
                     :generator (->> {:type :info, :f :crash}
                                     (gen/delay 10))}

    :none       {:nemesis   client/noop
                 :generator gen/void}))

(defn dup-validators
  "Takes a test. Constructs a map of nodes to the nodes whose validator keys
  they use instead of their own. If a node has no entry in the map, it
  generates its own validator key."
  [test]
  (if (:dup-validators test)
    (let [[orig & clones] (take 2 (:nodes test))]
      (zipmap clones (repeat orig)))))
    ; We need fewer than 1/3.
    ; (let [[orig & clones] (take (Math/floor (/ (count (:nodes test)) 3.01))
    ;                             (:nodes test))]
    ;   (zipmap clones (repeat orig)))))

(defn validator-weights
  "Takes a test. Computes a map of node names to voting amounts. When
  dup-validators are involved, allocates just shy of 2/3 votes to the
  duplicated key, assuming there's exactly one dup key."
  [test]
  (let [dup-vals (:dup-validators test)]
    (if-not (seq dup-vals)
      ; Equal weights
      (zipmap (:nodes test) (repeat 1))

      (let [{:keys [groups singles dups]} (dup-groups test)
            n                             (count groups)]
        (assert (= 1 (count dups))
                "Don't know how to handle more than one dup validator key")
        ; For super dup validators, we want the dup validator key to have just
        ; shy of 2/3 voting power. That means the sum of the normal nodes
        ; weights should be just over 1/3, so that the remaining node can make
        ; up just under 2/3rds of the votes by itself. Let a normal node's
        ; weight be 2. Then 2(n-1) is the combined voting power of the normal
        ; bloc. We can then choose 4(n-1) - 1 as the weight for the dup
        ; validator. The total votes are
        ;
        ;    2(n-1) + 4(n-1) - 1
        ;  = 6(n-1) - 1
        ;
        ; which implies a single dup node has fraction...
        ;
        ;    (4(n-1) - 1) / (6(n-1) - 1)
        ;
        ; which approaches 2/3 from 0 for n = 1 -> infinity, and if a single
        ; regular node is added to a duplicate node, a 2/3+ majority is
        ; available for all n >= 1.
        ;
        ; For regular dup validators, let an individual node have weight 2. The
        ; total number of individual votes is 2(n-1), which should be just
        ; larger than twice the number of dup votes, e.g:
        ;
        ;     2(n-1) = 2d + e
        ;
        ; where e is some small positive integer, and d is the number of dup
        ; votes. Solving for d:
        ;
        ;     (2(n-1) - e) / 2 = d
        ;          n - 1 - e/2 = d    ; Choose e = 2
        ;                n - 2 = d
        ;
        ; The total number of votes is therefore:
        ;
        ;     2(n-1) + n - 2
        ;   = 3n - 4
        ;
        ; So a dup validator alone has vote fraction:
        ;
        ;     (n - 2) / (3n - 4)
        ;
        ; which is always under 1/3. And with a single validator, it has vote
        ; fraction:
        ;
        ;     (n - 2) + 2 / (3n - 4)
        ;   =           n / (3n - 4)
        ;
        ; which is always over 1/3.
        (merge (zipmap (apply concat singles) (repeat 2))
               (zipmap (first dups) (repeat
                                      (if (:super-dup-validators test)
                                        (dec (* 4 (dec n)))
                                        (- n 2)))))))))
(defn deref-gen
  "Sometimes you need to build a generator not *now*, but *later*; e.g. because
  it depends on state that won't be available until the generator is actually
  invoked. Wrap a derefable returning a generator in this, and it'll be deref'ed
  only when asked for ops."
  [dgen]
  (reify gen/Generator
    (op [this test process]
      (gen/op @dgen test process))))

(defn workload
  "Given a test map, computes

      {:generator a generator of client ops
       :client    a client to execute those ops
       :model     a model to validate the history
       :checker   a map of checker names to checkers to run}."
  [test]
  (let [n (count (:nodes test))]
    (case (:workload test)
      :cas-register {:client    (cas-register-client)
                     :model     (model/cas-register)
                     :generator (independent/concurrent-generator
                                  (* 2 n)
                                  (range)
                                  (fn [k]
                                    (->> (gen/mix [w cas])
                                         (gen/reserve n r)
                                         (gen/stagger 1)
                                         (gen/limit 120))))
                     :final-generator nil
                     :checker {:linear (independent/checker
                                         (checker/linearizable))}}
      :set
      (let [keys (atom [])]
        {:client (set-client)
         :model  nil
         :generator (independent/concurrent-generator
                      n
                      (range)
                      (fn [k]
                        (swap! keys conj k)
                        (gen/phases
                          (gen/once {:type :invoke, :f :init})
                          (->> (range)
                               (map (fn [x]
                                      {:type :invoke
                                       :f    :add
                                       :value x}))
                               gen/seq
                               (gen/stagger 1/2)))))
         :final-generator (deref-gen
                            (delay
                              (locking keys
                                (independent/concurrent-generator
                                  n
                                  @keys
                                  (fn [k]
                                    (gen/each (gen/once {:type :invoke
                                                         :f :read})))))))
         :checker {:set (independent/checker (checker/set))}}))))


(defn test
  [opts]
  (let [validator-config (atom nil)
        test (merge
               tests/noop-test
               opts
               {:name (str "tendermint " (name (:workload opts)) " "
                           (name (:nemesis opts)))
                :os   debian/os
                :nonserializable-keys [:validator-config]
                :validator-config validator-config})
        db        (td/db test)
        nemesis   (nemesis test)
        workload  (workload test)
        checker   (checker/compose
                    (merge {:timeline (independent/checker (timeline/html))
                            :perf     (checker/perf)}
                           (:checker workload)))
        test    (merge test
                       {:db         db
                        :client     (:client workload)
                        :generator  (gen/phases
                                      (->> (:generator workload)
                                           (gen/nemesis (:generator nemesis))
                                           (gen/time-limit (:time-limit opts)))
                                      (gen/nemesis
                                        (gen/once {:type :info, :f :stop}))
                                      (gen/sleep 30)
                                      (gen/clients
                                        (:final-generator workload)))
                        :nemesis    (:nemesis nemesis)
                        :model      (:model workload)
                        :checker    checker})]
    test))
