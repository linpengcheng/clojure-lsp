(ns clojure-lsp.refactor.transform
  (:require
    [clojure-lsp.refactor.edit :as edit]
    [rewrite-clj.node :as n]
    [rewrite-clj.zip :as z]
    [rewrite-clj.custom-zipper.core :as cz]
    [clojure.tools.logging :as log]))

(defn result [zip-edits]
  (mapv (fn [zip-edit]
          (let [loc (:loc zip-edit)]
            (-> zip-edit
                (assoc :new-text (z/string loc))
                (dissoc :loc))))
        zip-edits))

(defn cycle-coll
  "Cycles collection between vector, list, map and set"
  [zloc]
  (let [sexpr (z/sexpr zloc)]
    (if (coll? sexpr)
      (let [node (z/node zloc)
            coerce-to-next (fn [sexpr children]
                             (cond
                               (map? sexpr) (n/vector-node children)
                               (vector? sexpr) (n/set-node children)
                               (set? sexpr) (n/list-node children)
                               (list? sexpr) (n/map-node children)))]
        [{:range (meta (z/node zloc))
          :loc (z/replace zloc (coerce-to-next sexpr (n/children node)))}])
      [])))

(defn raise [zloc]
  (if-let [ploc (z/up zloc)]
    (z/replace ploc (z/node zloc))
    zloc))

(defn wrap-around [zloc tag]
  (let [node (z/node zloc)]
    (-> zloc
        (z/replace (case tag
                       :list (n/list-node [])
                       :vector (n/vector-node [])
                       :set (n/set-node [])
                       :map (n/map-node [])))
        (z/insert-child node))))

(defmacro zspy [loc]
  `(do
     (log/warn '~loc (pr-str (z/sexpr ~loc)))
     ~loc))

(defmacro zass [loc sexpr]
  `(do
     (assert (= '~sexpr (z/sexpr ~loc)) (pr-str (z/sexpr ~loc)))
     ~loc))

(defn thread-sym
  [zloc sym]
  (let [movement (if (= '-> sym) z/right z/rightmost)]
    (if-let [first-loc (-> zloc (edit/find-op) movement)]
      (let [first-node (z/node first-loc)
            parent-op (z/sexpr (z/left zloc))
            threaded? (= sym parent-op)
            meta-node (cond-> zloc
                        threaded? z/up
                        :always (-> z/node meta))
            result-loc (-> first-loc
                           (z/remove)
                           (z/up)
                           ((fn [loc] (cond-> loc
                                        (edit/single-child? loc) (-> z/down raise)
                                        threaded? (-> (z/insert-left first-node) z/up)
                                        (not threaded?) (-> (wrap-around :list)
                                                            (z/insert-child first-node)
                                                            (z/insert-child sym))))))]
        [{:range meta-node
          :loc result-loc}])
      [])))

(defn thread-first
  [zloc]
  (thread-sym zloc '->))

(defn thread-last
  [zloc]
  (thread-sym zloc '->>))

(defn thread-all
  [zloc sym]
  (loop [[{:keys [loc]} :as result] (thread-sym zloc sym)]
    (if (z/down (z/right (z/down loc)))
      (recur (thread-sym (z/right (z/down loc)) sym))
      result)))

(defn thread-first-all
  [zloc]
  (thread-all zloc '->))

(defn thread-last-all
  [zloc]
  (thread-all zloc '->>))

(defn move-to-let
    "Adds form and symbol to a let further up the tree"
    [zloc binding-name]
    (let [bound-node (z/node zloc)
          binding-sym (symbol binding-name)]
      (if-let [let-loc (edit/find-ops-up zloc 'let)] ; find first ancestor let
        (let [{:keys [col]} (meta (z/node (z/right let-loc))) ;; indentation of bindings
              new-let-loc (-> zloc
                              (z/insert-right binding-sym) ; replace it with binding-symbol
                              (z/remove) ; remove bound-node and newline
                              (edit/find-ops-up zloc 'let) ; move to ancestor let
                              (z/right) ; move to binding
                              (cz/append-child (n/newlines 1))
                              (cz/append-child (n/spaces col)) ; insert let and bindings backwards
                              (z/append-child binding-sym) ; add binding symbol
                              (z/append-child bound-node) ; read bound node into let bindings
                              (z/up))]
          [{:range (meta (z/node (z/up let-loc)))
            :loc new-let-loc}])
        [])))

(comment
  (defn introduce-let
    "Adds a let around the current form."
    [zloc [binding-name]]
    (let [sym (symbol binding-name)]
      (-> zloc
          (p/wrap-around :list) ; wrap with new let list
          (z/up) ; move to new let list
          (z/insert-child 'let) ; add let
          (zz/append-child (n/newlines 1)) ; add new line after location
          (z/append-child sym) ; add new symbol to body of let
          (z/down) ; enter let list
          (z/next) ; skip 'let
          (p/wrap-around :vector) ; wrap binding vec around form
          (z/up) ; go to vector
          (z/insert-child sym) ; add new symbol as binding
          (z/leftmost) ; back to let
          (edit/join-let)))) ; join if let above

  ;; TODO replace bound forms that are being expanded around
  (defn expand-let
    "Expand the scope of the next let up the tree."
    [zloc _]
    ;; TODO check that let is also leftmost?
    (let [let-loc (z/find-value zloc z/prev 'let)
          bind-node (z/node (z/next let-loc))]
      (if (edit/parent-let? let-loc)
        (edit/join-let let-loc)
        (-> let-loc
            (z/up) ; move to form above
            (z/splice) ; splice in let
            (z/right)
            (z/right)
            (edit/remove-left) ; remove let
            (edit/remove-left) ; remove binding
            (z/leftmost) ; go to front of form above
            (z/up) ; go to form container
            (p/wrap-around :list) ; wrap with new let list
            (z/up) ; move to new let list
            (zz/insert-child (n/newlines 1)) ; insert let and bindings backwards
            (z/insert-child bind-node)
            (z/insert-child 'let)
            (z/leftmost) ; go to let
            (edit/join-let))))) ; join if let above

  (defn extract-def
    [zloc [def-name]]
    (let [def-sexpr (z/sexpr zloc)
          def-node (z/node zloc)
          def-sym (symbol def-name)]
      (-> zloc
          (edit/to-top)
          (edit/mark-position :first-occurrence)
          (edit/replace-all-sexpr def-sexpr def-sym true)
          (edit/find-mark :first-occurrence)
          (zz/insert-left (n/coerce (list 'def def-sym))) ; add declare
          (zz/insert-left (n/newlines 2)) ; add new line after location
          (z/left)
          (zz/append-child (n/newlines 1))
          (z/append-child def-node))))

  (defn add-declaration
    "Adds a declaration for the current symbol above the current top level form"
    [zloc _]
    (let [node (z/sexpr zloc)]
      (if (symbol? node)
        (-> zloc
            (edit/to-top)
            (zz/insert-left (n/coerce (list 'declare node))) ; add declare
            (zz/insert-left (n/newlines 2)) ; add new line after location
            (z/left))
        zloc)))


  (defn cycle-if
    "Cycles between if and if-not form"
    [zloc _]
    (if-let [if-loc (z/find-value zloc z/prev #{'if 'if-not})] ; find first ancestor if
      (-> if-loc
          (z/insert-left (if (= 'if (z/sexpr if-loc)) 'if-not 'if)) ; add inverse if / if-not
          (z/remove) ; remove original if/if-not
          (z/rightmost) ; Go to last child (else form)
          (edit/transpose-with-left)) ; Swap children
      zloc))

  (defn ensure-list
    [zloc]
    (if (z/seq? zloc)
      (z/down zloc)
      (p/wrap-around zloc :list)))

  (defn unwind-thread
    [zloc _]
    (let [oploc (edit/find-op zloc)
          thread-type (z/sexpr oploc)]
      (if (contains? #{'-> '->>} thread-type)
        (let [first-loc (z/right oploc)
              first-node (z/node first-loc)
              move-to-insert-pos (if (= '-> thread-type)
                                   z/leftmost
                                   z/rightmost)]
          (-> first-loc
              (z/right) ; move to form to unwind into
              (edit/remove-left) ; remove threaded form
              (ensure-list) ; make sure we're dealing with a wrapped fn
              (move-to-insert-pos) ; move to pos based on thread type
              (z/insert-right first-node)
              (z/up)
              ((fn [loc]
                 (if (z/rightmost? loc)
                   (p/raise loc)
                   loc)))
              (z/up)))
        zloc)))

  (defn unwind-all
    [zloc _]
    (loop [loc (unwind-thread zloc nil)]
      (let [oploc (edit/find-op loc)
            thread-type (z/sexpr oploc)]
        (if (contains? #{'-> '->>} thread-type)
          (recur (unwind-thread loc nil))
          loc))))

  ;; TODO will insert duplicates
  ;; TODO handle :type and :macro
  (defn add-candidate
    "Add a lib spec to ns form - `missing` is the package or class and `missing-type` is one of `#{:ns :class :type :macro}`"
    [zloc [missing missing-type sym-ns]]
    (-> zloc
        (edit/find-namespace)
        (edit/mark-position :reformat)
        (cond->
          (= missing-type :class)
          (->
            (edit/find-or-create-libspec :import) ; go to import
            (zz/insert-right (n/newlines 1))
            (z/insert-right (symbol missing))) ; add class

          (= missing-type :ns)
          (->
            (edit/find-or-create-libspec :require) ; go to require
            (zz/insert-right (n/newlines 1))
            (z/insert-right [(symbol missing)]) ; add require vec and ns
            (z/right))

          (and sym-ns (= missing-type :ns)) ; if there was a requested ns `str/trim`
          (->
            (z/append-child :as) ; add :as
            (z/append-child (symbol sym-ns)))))) ; as prefix

  (defn replace-ns
    [zloc [new-ns]]
    (-> zloc
        (edit/find-namespace)
        (z/insert-right new-ns)
        (z/remove)
        (edit/find-namespace)))

  (defn cycle-op
    [zloc a-op b-op]
    (if-let [oploc (edit/find-ops-up zloc a-op b-op)]
      (let [thread-type (z/sexpr oploc)]
        (cond
          (= a-op thread-type) (z/replace oploc b-op)
          (= b-op thread-type) (z/replace oploc a-op)
          :else zloc))
      zloc))

  (defn cycle-thread
    [zloc _]
    (cycle-op zloc '-> '->>))

  (defn cycle-privacy
    [zloc _]
    (cycle-op zloc 'defn 'defn-))

  (defn function-from-example
    [zloc _]
    (let [op-loc (edit/find-op zloc)
          example-loc (z/up (edit/find-op zloc))
          child-sexprs (n/child-sexprs (z/node example-loc))
          fn-name (first child-sexprs)
          args (for [[i arg] (map-indexed vector (rest child-sexprs))]
                 (if (symbol? arg)
                   arg
                   (symbol (str "arg" (inc i)))))]
      (-> example-loc
          (edit/to-top)
          (zz/insert-left (n/coerce `(~'defn ~fn-name [~@args]))) ; add declare
          (zz/insert-left (n/newlines 2))))) ; add new line after location

  (defn extract-function
    [zloc [fn-name used-locals]]
    (let [expr-loc (z/up (edit/find-op zloc))
          expr-node (z/node expr-loc)
          expr (z/sexpr expr-loc)
          fn-sym (symbol fn-name)
          used-syms (mapv symbol used-locals)]
      (-> expr-loc
          (z/replace `(~fn-sym ~@used-syms))
          (edit/mark-position :reformat)
          (edit/mark-position :new-cursor)
          (edit/to-top)
          (zz/insert-left (n/coerce (list 'defn fn-sym used-syms)))
          (zz/insert-left (n/newlines 2))
          (z/left)
          (zz/append-child (n/newlines 1))
          (z/append-child expr-node))))

  (defn format-form
    [zloc _]
    (edit/format-form zloc))

  (defn format-all
    [zloc _]
    (edit/format-all zloc)))
