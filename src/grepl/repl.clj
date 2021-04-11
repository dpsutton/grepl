(ns grepl.repl
  (:require [asami.core :as d]
            [clojure.edn :as edn]
            [clojure.pprint :refer (pprint)]
            clojure.main
            clojure.core.server))

(def db-uri "asami:mem://grepl")
(d/create-database db-uri)

(def conn (d/connect db-uri))

(defn ->tx-data [form]
  (let [ids (atom 0)
        ;; tree-seq but allowing us to annotate children with parent
        walk (fn walk [parent-id node]
               (let [id (swap! ids dec)]
                 (lazy-seq
                   (cons {:grepl/form (pr-str node)
                          :grepl/parent parent-id
                          :grepl/root -1
                          :db/id id}
                         (when (seqable? node)
                           (mapcat (partial walk id) (if (map? node) (concat (keys node) (vals node))
                                                         (seq node))))))))
        walked (walk -1 form)]
    (concat (map #(dissoc % :grepl/parent :grep/root) walked)
            (mapcat (fn [node] [[:db/add (:db/id node) :grepl/parent (:grepl/parent node)]
                                [:db/add (:db/id node) :grepl/root -1]])
                    walked))))

(comment
  (def db (d/db conn))
  (def graph (d/graph db)))

(def results-xf (map (fn [x] (try (edn/read-string x) (catch Throwable _ x)))))

(defn root-of [form]
  (let [db (d/db conn)]
    (->> (d/q '[:find [?root-form ...]
                :in $ ?form
                :where
                [?e :grepl/form ?form]
                [?e :grepl/root ?root]
                [?root :grepl/form ?root-form]]
              db
              (pr-str form))
         (map (fn [x] (try (edn/read-string x) (catch Throwable _ x))))
         (run! pprint))))

(comment
  (let [form 'le
        db (d/db conn)]
    (->> (d/q '[:find [?root-form ...]
                :in $ ?regex
                :where
                [?e :grepl/form ?form]
                [(re-find ?regex ?form)]
                [?e :grepl/root ?root]
                [?root :grepl/form ?root-form]]
              db
              (re-pattern (pr-str form)))
         (map (fn [x] (try (edn/read-string x) (catch Throwable _ x))))
         (run! pprint))))

(defn parent-of [form]
  (let [db (d/db conn)]
    (->> (d/q '[:find [?parent-form ...]
                :in $ ?form
                :where
                [?e :grepl/form ?form]
                [?e :grepl/parent ?parent]
                [?parent :grepl/form ?parent-form]]
              db
              (pr-str form))
         (map (fn [x] (try (edn/read-string x) (catch Throwable _ x))))
         (run! pprint))))

(comment
  (require '[vlaaad.reveal :as reveal])
  (add-tap (reveal/ui))

  (clojure.main/repl
    :eval (fn [form]
            (if (and (sequential? form)
                     (keyword? (first form))
                     (#{"grepl"} (namespace (first form))))
              (case (first form)
                  :grepl/root (root-of (second form))
                  :grepl/parent (parent-of (second form)))
              (do (d/transact conn {:tx-data (->tx-data form)})
                  (eval form))))
    ; :prompt (fn [] (printf "history aware> "))
    :read clojure.core.server/repl-read))
