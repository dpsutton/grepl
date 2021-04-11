## GREPL

A way to grep your repl. It stores all repl forms in [asami](https://github.com/threatgrid/asami). You can search the repl history for forms, returning either the parent containing form or the entire (root) form.

```clojure
grepl.repl=> (let [c 2 d 4] (+ c d))  ;; enter a form at the repl
6
grepl.repl=> (:grepl/parent d)        ;; query for the parents of d, of which there are two
[c 2 d 4]
(+ c d)
nil
grepl.repl=> (:grepl/root d)          ;; query for the root form, of which there is one
(let [c 2 d 4] (+ c d))
nil
```

### to use

```clojure
(require '[grepl.repl :as grepl])
(grepl/grepl)
```

To exit, just type `:repl/quit`.

## Data storage

Grepl uses the [asami](https://github.com/threatgrid/asami) graph database. It adds a step to the eval stage of a repl that breaks apart a submitted form and sticks in in asami. For instance, the form `(let [a 1 b 2] (+ a b))` gets broken into:

```clojure
grepl.repl=> (pprint (->tx-data '(let [a 1 b 2] (+ a b)))) ;; quoted here since used at the repl
({:grepl/form "(let [a 1 b 2] (+ a b))", :db/id -1}
 {:grepl/form "let", :db/id -2}
 {:grepl/form "[a 1 b 2]", :db/id -3}
 {:grepl/form "a", :db/id -5}
 {:grepl/form "1", :db/id -6}
 {:grepl/form "b", :db/id -7}
 {:grepl/form "2", :db/id -8}
 {:grepl/form "(+ a b)", :db/id -4}
 {:grepl/form "+", :db/id -9}
 {:grepl/form "a", :db/id -10}
 {:grepl/form "b", :db/id -11}
 [:db/add -1 :grepl/parent -1]
 [:db/add -1 :grepl/root -1]
 [:db/add -2 :grepl/parent -1]
 [:db/add -2 :grepl/root -1]
 [:db/add -3 :grepl/parent -1]
 [:db/add -3 :grepl/root -1]
 [:db/add -5 :grepl/parent -3]
 [:db/add -5 :grepl/root -1]
 [:db/add -6 :grepl/parent -3]
 [:db/add -6 :grepl/root -1]
 [:db/add -7 :grepl/parent -3]
 [:db/add -7 :grepl/root -1]
 [:db/add -8 :grepl/parent -3]
 [:db/add -8 :grepl/root -1]
 [:db/add -4 :grepl/parent -1]
 [:db/add -4 :grepl/root -1]
 [:db/add -9 :grepl/parent -4]
 [:db/add -9 :grepl/root -1]
 [:db/add -10 :grepl/parent -4]
 [:db/add -10 :grepl/root -1]
 [:db/add -11 :grepl/parent -4]
 [:db/add -11 :grepl/root -1])
```

This tree-seq's the form, puts it in the database along with a pointer to its parent sexp and the root sexp. In this way if you did `(:grepl/parent b)` it will see two nodes who's value is `b` and get the parent form, yielding `[a 1 b 2]` (the binding form) and `(+ a b)` the addition form. If you query for the root form you would get the single `(let [a 1 b 2] (+ a b))` form.

## Limitations and Future work

Currently it only allows for exact matches. This can be annoying if you wanted to see how you constructed a LineNumberingPushbackReader and need to remember that you need to query for `LineNumberingPushbackReader.` since you used its constructor. I need to figure out how this api works.

It also pprint's all results and doesn't return data. In the future it would be nice to have forms datafiable so you could walk up the sexp parent by parent to get as much or as little context as desired.

At the moment it also doesn't limit the number of returned results. I need to add this functionality so that perhaps it returns the latest N results.

## Really cool things that could be added in the future

- timing all top level forms automatically. Functionally wise, this should be easy. The top level form always has temp-id -1 and the transaction returns a map of temp-ids to actual ids. Just transact the top level form with a start time and add an end time and duration at the end
- general purpose debugger: CIDER has a very neat debugger that instruments code. That could be used but just take out the interactivity of it and annotate each subform with it's value. Then have `(:grepl/annotate <form>)` use the debugger, compute all the subvalues, and then print out the form annotated with the intermediate values. I suspect the UI portion of this would be the hardest.
