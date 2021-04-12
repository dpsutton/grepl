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
        branch? (fn [node]
                  (and (seqable? node) (not (string? node))))
        children (fn [node]
                   (if (map? node)
                     (concat (keys node) (vals node))
                     (seq node)))
        ;; tree-seq but allowing us to annotate children with parent
        walk (fn walk [parent-id node]
               (let [id (swap! ids dec)]
                 (lazy-seq
                   (cons {:grepl/form (pr-str node)
                          :grepl/parent parent-id
                          :grepl/root -1
                          :db/id id}
                         (when (branch? node)
                           (mapcat (partial walk id) (children node)))))))
        walked (walk -1 form)]
    (concat (map #(dissoc % :grepl/parent :grepl/root) walked)
            (mapcat (fn [node] [[:db/add (:db/id node) :grepl/parent (:grepl/parent node)]
                                [:db/add (:db/id node) :grepl/root -1]])
                    walked))))

(comment
  (def db (d/db conn))
  (def graph (d/graph db)))

(defn root-of [db form]
  (d/q '[:find [?root-form ...]
         :in $ ?form
         :where
         [?e :grepl/form ?form]
         [?e :grepl/root ?root]
         [?root :grepl/form ?root-form]]
       db
       (pr-str form)))

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

(defn parent-of [db form]
  (d/q '[:find [?parent-form ...]
         :in $ ?form
         :where
         [?e :grepl/form ?form]
         [?e :grepl/parent ?parent]
         [?parent :grepl/form ?parent-form]]
       db
       (pr-str form)))

(def results-xf (map (fn [x] (try (edn/read-string x) (catch Throwable _ x)))))

(defn pprint-results
  [results]
  (binding [*print-meta* false]
    (transduce results-xf
               (completing (fn [_ r] (pprint r)))
               nil
               results)))

(comment

  (require '[vlaaad.reveal :as reveal])
  (add-tap (reveal/ui))

  (clojure.main/repl
    :eval (fn [form]
            (if (and (sequential? form)
                     (keyword? (first form))
                     (#{"grepl"} (namespace (first form))))
              (let [db (d/db conn)]
                (case (first form)
                  :grepl/root (pprint-results (root-of db (second form)))
                  :grepl/parent (pprint-results (parent-of db (second form)))))
              (do (d/transact conn {:tx-data (->tx-data form)})
                  (eval form))))
                                        ; :prompt (fn [] (printf "history aware> "))
    :read clojure.core.server/repl-read))

(defn grepl
  "Start a clojure.main/repl which stores repl history in the graph
  database asami. To search for the history, type `(:grepl/parent
  <form>)` or `(:grepl/root <form>)`. There is no need to quote forms
  to prevent evaluation.

  Example:
  grepl.repl=> (let [c 2 d 4] (+ c d))  ;; enter a form at the repl
  6
  grepl.repl=> (:grepl/parent d)        ;; query for the parents of d, of which there are two
  [c 2 d 4]
  (+ c d)
  nil
  grepl.repl=> (:grepl/root d)          ;; query for the root form, of which there is one
  (let [c 2 d 4] (+ c d))
  nil"
  []
  (clojure.main/repl
    :eval (fn [form]
            (if (and (sequential? form)
                     (keyword? (first form))
                     (#{"grepl"} (namespace (first form))))
              (let [db (d/db conn)]
                (case (first form)
                  :grepl/root (pprint-results (root-of db (second form)))
                  :grepl/parent (pprint-results (parent-of db (second form)))))
              (do (d/transact conn {:tx-data (->tx-data form)})
                  (eval form))))
    :read clojure.core.server/repl-read))
