(ns grepl.repl
  (:require [asami.core :as d]
            [clojure.edn :as edn]
            [clojure.pprint :refer (pprint)]
            clojure.main
            clojure.core.server)
  (:import java.util.regex.Pattern))

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

(defn root%-of [db form]
  (d/q '[:find [?root-form ...]
         :in $ ?regex
         :where
         [?e :grepl/form ?form]
         [(re-find ?regex ?form)]
         [?e :grepl/root ?root]
         [?root :grepl/form ?root-form]]
       db
       (if (instance? Pattern form)
         form
         (re-pattern (pr-str form)))))

(defn parent-of [db form]
  (d/q '[:find [?parent-form ...]
         :in $ ?form
         :where
         [?e :grepl/form ?form]
         [?e :grepl/parent ?parent]
         [?parent :grepl/form ?parent-form]]
       db
       (pr-str form)))

(defn parent%-of
  [db form]
  (d/q '[:find [?parent-form ...]
         :in $ ?regex
         :where
         [?e :grepl/form ?form]
         [(re-find ?regex ?form)]
         [?e :grepl/parent ?parent]
         [?parent :grepl/form ?parent-form]]
       db
       (if (instance? Pattern form)
         form
         (re-pattern (pr-str form)))))

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

  )

(defn grepl
  "Start a `clojure.main/repl` which stores repl history in the graph
  database asami. To search for the history, type `(?grepl/parent
  <form>)` or `(?grepl/root <form>)`. There is no need to quote forms
  to prevent evaluation. To search for partial matches append a % at the end:

  (?grepl/root% le)
  (?grepl/parent% le) ;; le for a partial match on let

  Or use a regex for fine control

  (?grepl/root% #\"le\")

  Example:
  user=> (let [c 2 d 4] (+ c d))  ;; enter a form at the repl
  6
  user=> (?grepl/parent d)        ;; query for the parents of d, of which there are two
  [c 2 d 4]
  (+ c d)
  nil
  user=> (?grepl/root d)          ;; query for the root form, of which there is one
  (let [c 2 d 4] (+ c d))
  nil"
  []
  (clojure.main/repl
    :eval (fn [form]
            (if (and (sequential? form)
                     (symbol (first form))
                     (#{"?grepl"} (namespace (first form))))
              (let [db (d/db conn)]
                (case (first form)
                  ?grepl/root    (pprint-results (root-of db (second form)))
                  ?grepl/root%   (pprint-results (root%-of db (second form)))
                  ?grepl/parent  (pprint-results (parent-of db (second form)))
                  ?grepl/parent% (pprint-results (parent%-of db (second form)))
                  ?grepl/reset   (do (d/delete-database db-uri)
                                     (alter-var-root #'conn (constantly (d/connect db-uri)))
                                     true)))
              (do (d/transact conn {:tx-data (->tx-data form)})
                  (eval form))))
    :read clojure.core.server/repl-read))

(comment
  (let [db (d/db conn)
        root :tg/node-19485]
    (d/q '[:find [?form ...]
           :in $ ?root
           :where
           [?e :grepl/root ?root]
           [?e :grepl/form ?form]]
         db root))

  (def conn (d/connect db-uri))
  (d/delete-database db-uri))
