(ns clojure-lsp.parser
  (:require
    [clojure-lsp.clojure-core :as cc]
    [clojure.set :as set]
    [clojure.spec.alpha :as s]
    [clojure.string :as string]
    [clojure.tools.logging :as log]
    [clojure.walk :as walk]
    [rewrite-clj.node :as n]
    [rewrite-clj.zip :as z]
    [rewrite-clj.custom-zipper.core :as cz]
    [rewrite-clj.zip.edit :as ze]
    [rewrite-clj.zip.find :as zf]
    [rewrite-clj.zip.move :as zm]
    [rewrite-clj.zip.walk :as zw]
    [rewrite-clj.zip.subedit :as zsub]))

(declare find-references*)
(declare parse-destructuring)

(def default-env
  {:ns 'user
   :requires #{'clojure.core}
   :refers (->> cc/core-syms
                (mapv (juxt identity (constantly 'clojure.core)))
                (into {}))
   :aliases {}
   :publics #{}
   :usages []})

(defmacro zspy [loc]
  `(do
     (log/warn '~loc (pr-str (z/sexpr ~loc)))
     ~loc))

(defn skip-over [loc]
  (if (zm/down loc)
    (->> loc
         zm/down
         zm/rightmost
         (z/skip z/up z/rightmost?)
         z/right)
    (zm/next loc)))

(defn qualify-ident [ident {:keys [aliases publics refers requires ns] :as context} scoped]
  (when (ident? ident)
    (let [ident-ns (some-> (namespace ident) symbol)
          ident-name (name ident)
          alias->ns (set/map-invert aliases)
          ctr (if (symbol? ident) symbol keyword)]
      (if (simple-ident? ident)
        (cond
          (keyword? ident) {:sym ident}
          (contains? scoped ident) {:sym (ctr (name (get-in scoped [ident :ns])) ident-name)}
          (contains? publics ident) {:sym (ctr (name ns) ident-name)}
          (contains? refers ident) {:sym (ctr (name (get refers ident)) ident-name)}
          :else {:sym (ctr (name (gensym)) ident-name) :tags #{:unknown}})
        (cond
          (contains? alias->ns ident-ns)
          {:sym (ctr (name (alias->ns ident-ns)) ident-name)}

          (contains? requires ident-ns)
          {:sym ident}

          :else
          {:sym (ctr (name (gensym)) ident-name) :tags #{:unknown}})))))

(defn add-reference [context scoped node extra]
  (vswap! context
          (fn [context]
            (update context :usages
                    (fn [usages]
                      (let [{:keys [row end-row col end-col] :as m} (meta node)
                            sexpr (n/sexpr node)
                            scope-bounds (get-in scoped [sexpr :bounds])
                            ident-info (qualify-ident sexpr context scoped)]
                        (conj usages (cond-> {:sexpr sexpr
                                              :row row
                                              :end-row end-row
                                              :col col
                                              :end-col end-col}
                                       :always (merge ident-info)
                                       (seq extra) (merge extra)
                                       (seq scope-bounds) (assoc :scope-bounds scope-bounds)))))))))

(defn destructure-map [map-loc scope-bounds context scoped]
  (loop [key-loc (z/down (zsub/subzip map-loc))
         scoped scoped]
    (if (and key-loc (not (z/end? key-loc)))
      (let [key-sexpr (z/sexpr key-loc)
            val-loc (z/right key-loc)]
        (cond
          (= :keys key-sexpr)
          (recur (skip-over val-loc)
                 (loop [child-loc (z/down val-loc)
                        scoped scoped]
                   (let [sexpr (z/sexpr child-loc)
                         scoped-ns (gensym)
                         new-scoped (assoc scoped sexpr {:ns scoped-ns :bounds scope-bounds})]
                     (add-reference context scoped (z/node child-loc) {:tags #{:declare :param}
                                                                       :scope-bounds scope-bounds
                                                                       :sym (symbol (name scoped-ns)
                                                                                    (name sexpr))
                                                                       :sexpr sexpr})
                     (if (z/rightmost? child-loc)
                       new-scoped
                       (recur (z/right child-loc) new-scoped)))))

          (= :as key-sexpr)
          (let [val-node (z/node val-loc)
                sexpr (n/sexpr val-node)
                scoped-ns (gensym)
                new-scoped (assoc scoped sexpr {:ns scoped-ns :bounds scope-bounds})]
            (add-reference context scoped (z/node val-loc) {:tags #{:declare :param}
                                                                     :scope-bounds scope-bounds
                                                                     :sym (symbol (name scoped-ns)
                                                                                  (name sexpr))
                                                                     :sexpr sexpr})
            (recur (z/right val-loc) new-scoped))

          (keyword? key-sexpr)
          (recur (skip-over val-loc) scoped)

          (not= '& key-sexpr)
          (recur (z/right val-loc) (parse-destructuring key-loc scope-bounds context scoped))

          :else
          (recur (skip-over val-loc) scoped)))
      scoped)))

(comment
  (let [context (volatile! {})]
    #_(do (destructure-map (z/of-string "{:keys [a b]}") {} context {}) (map :sym (:usages @context)))
    (map :sym (:usages (find-references
                        "(let [a (b c d)])")))))

(defn handle-rest
  "Crawl each form from `loc` to the end of the parent-form
  `(fn [x 1] | (a) (b) (c))`
  With cursor at `|` find references for `(a)`, `(b)`, and `(c)`"
  [loc context scoped]
  (loop [sub-loc loc]
    (when sub-loc
      (find-references* (zsub/subzip sub-loc) context scoped)
      (recur (zm/right sub-loc)))))

(defn parse-destructuring [param-loc scope-bounds context scoped]
  (loop [param-loc (zsub/subzip param-loc)
         scoped scoped]
    ;; MUTATION Updates scoped AND adds declared param references
    (if (and param-loc (not (z/end? param-loc)))
      (let [node (z/node param-loc)
            sexpr (n/sexpr node)]
        (cond
          ;; TODO handle map and seq destructing
          (symbol? sexpr)
          (let [scoped-ns (gensym)
                new-scoped (assoc scoped sexpr {:ns scoped-ns :bounds scope-bounds})]
            (add-reference context new-scoped node {:tags #{:declare :param}
                                                    :scope-bounds scope-bounds
                                                    :sym (symbol (name scoped-ns)
                                                                 (name sexpr))
                                                    :sexpr sexpr})
            (recur (z/next param-loc) new-scoped))

          (vector? sexpr)
          (recur (z/next param-loc) scoped)

          (map? sexpr)
          (recur (skip-over param-loc) (destructure-map param-loc scope-bounds context scoped))

          :else
          (recur (skip-over param-loc) scoped)))
      scoped)))


(defn end-bounds [loc]
  (select-keys (meta (z/node loc)) [:end-row :end-col]))

(defn parse-bindings [bindings-loc context end-scope-bounds scoped]
  (try
    (loop [binding-loc (zm/down (zsub/subzip bindings-loc))
           scoped scoped]

      (let [not-done? (and binding-loc (not (z/end? binding-loc)))]
        ;; MUTATION Updates scoped AND adds declared param references AND adds references in binding vals)
        (cond
          (and not-done? (= :uneval (z/tag binding-loc)))
          (recur (skip-over binding-loc) scoped)

          not-done?
          (let [right-side-loc (z/right binding-loc)
                binding-sexpr (z/sexpr binding-loc)]
            ;; Maybe for/doseq needs to use different bindings
            (cond
              (= :let binding-sexpr)
              (parse-bindings right-side-loc context end-scope-bounds scoped)

              (#{:when :while} binding-sexpr)
              (handle-rest (zsub/subzip right-side-loc) context scoped)

              :else
              (let [{:keys [end-row end-col]} (meta (z/node (or (z/right right-side-loc) (z/up right-side-loc) bindings-loc)))
                    scope-bounds (assoc end-scope-bounds :row end-row :col end-col)
                    new-scoped (parse-destructuring binding-loc scope-bounds context scoped)]
                (handle-rest (zsub/subzip right-side-loc) context scoped)
                (recur (skip-over right-side-loc) new-scoped))))

          :else
          scoped)))
    (catch Throwable e
      (log/warn "bindings" (.getMessage e) (z/sexpr bindings-loc) (z/sexpr (z/up bindings-loc)))
      (throw e))))

(defn parse-params [params-loc context scoped]
  (try
    (let [{:keys [row col]} (meta (z/node params-loc))
          {:keys [end-row end-col]} (meta (z/node (z/up params-loc)))
          scoped-ns (gensym)
          scope-bounds {:row row :col col :end-row end-row :end-col end-col}]
      (loop [param-loc (z/down params-loc)
             scoped scoped]
        (let [new-scoped (parse-destructuring param-loc scope-bounds context scoped)]
          (if (z/rightmost? param-loc)
            new-scoped
            (recur (z/right param-loc) new-scoped)))))
    (catch Exception e
      (log/warn "params" (.getMessage e) (z/sexpr params-loc))
      (throw e))))


(defn handle-comment
  [op-loc loc context scoped]
  ;; Ignore contents of comment
  nil)

(defn handle-ns
  [op-loc loc context scoped]
  ;; TODO add refers to usages and add full libs to usages
  (let [conformed (s/conform (:args (s/get-spec 'clojure.core/ns)) (rest (z/sexpr loc)))
        libs (:body (second (first (filter (comp #{:require} first) (:clauses conformed)))))
        prefix-symbol (fn [prefix sym]
                        (if prefix
                          (symbol (str (name prefix) "." (name sym)))
                          sym))
        expand-lib-spec (fn [prefix [tag arg]]
                          (if (= :lib tag)
                            {:lib (prefix-symbol prefix arg)}
                            (update arg :lib #(prefix-symbol prefix %))))
        expand-prefix-list (fn [{:keys [prefix libspecs]}]
                             (mapv (partial expand-lib-spec prefix) libspecs))

        libspecs (reduce (fn [libspecs libspec-or-prefix-list]
                           (let [[tag arg] libspec-or-prefix-list]
                             (into libspecs
                                   (cond
                                     (= :libspec tag) [(expand-lib-spec nil arg)]
                                     (= :prefix-list tag) (expand-prefix-list arg)))))
                         []
                         libs)
        require-loc (-> loc
                        (z/down)
                        (z/find z/right (fn [node]
                                          (let [sexpr (z/sexpr node)]
                                            (and (seq? sexpr) (= :require (first sexpr)))))))
        require-node (-> (or require-loc loc)
                         (z/down)
                         (z/rightmost)
                         (z/node)
                         (meta))]
    (doseq [{:keys [lib options]} libspecs]
      (vswap! context (fn [env]
                        (let [{:keys [as refer]} options]
                          (cond-> env
                            :always (update :requires conj lib)
                            as (update :aliases conj [lib as])
                            (and refer (= :syms (first refer))) (update :refers into (map vector (second refer) (repeat lib))))))))
    (vswap! context assoc :ns (:name conformed) :require-pos {:add-require? (not require-loc)
                                                              :row (:end-row require-node)
                                                              :col (:end-col require-node)})))

(defn handle-let
  [op-loc loc context scoped]
  (let [bindings-loc (zf/find-tag op-loc :vector)
        scoped (parse-bindings bindings-loc context (end-bounds loc) scoped)]
    (handle-rest (z/right bindings-loc) context scoped)))

(defn handle-def
  [op-loc loc context scoped]
  (let [def-sym (z/node (z/right op-loc))]
    (vswap! context update :publics conj (n/sexpr def-sym))
    (add-reference context scoped def-sym {:tags #{:declare :public}})
    (handle-rest (z/right (z/right op-loc))
                 context scoped)))

(defn handle-fn
  [op-loc loc context scoped]
  (let [params-loc (z/find-tag op-loc :vector)
        body-loc (z/right params-loc)]
    (->> (parse-params params-loc context scoped)
         (handle-rest body-loc context))))

(defn handle-defn
  [op-loc loc context scoped]
  (let [defn-loc (z/right op-loc)
        defn-sym (z/node defn-loc)
        multi? (= :list (z/tag (z/find defn-loc (fn [loc] (#{:vector :list} (z/tag loc))))))]
    (vswap! context update :publics conj (n/sexpr defn-sym))
    ;; TODO handle multi signatures
    (add-reference context scoped defn-sym {:tags #{:declare :public} :signature (z/string (z/find-tag defn-loc z/next :vector))})
    (if multi?
      (loop [list-loc (z/find-tag defn-loc :list)]
        (let [params-loc (z/down list-loc)
              body-loc (z/right params-loc)]
          (->> (parse-params params-loc context scoped)
               (handle-rest body-loc context)))
        (when-let [next-list (z/find-next-tag list-loc :list)]
          (recur next-list)))
      (let [params-loc (z/find-tag defn-loc :vector)
            body-loc (z/right params-loc)]
        (->> (parse-params params-loc context scoped)
             (handle-rest body-loc context))))))

(comment
  (as->
    (find-references "(defmacro x ([{:keys [a]} {}] `a) ([a b c] 'a))") $
    (dissoc $ :refers)
    (:usages $)
    (map (juxt :sym :tags) $)))

(defn handle-defmacro
  [op-loc loc context scoped]
  (let [defn-loc (z/right op-loc)
        defn-sym (z/node defn-loc)
        multi? (= :list (z/tag (z/find defn-loc (fn [loc]
                                                  (#{:vector :list} (z/tag loc))))))]
    (vswap! context update :publics conj (n/sexpr defn-sym))
    (add-reference context scoped defn-sym {:tags #{:declare :public}})
    (if multi?
      (loop [list-loc (z/find-tag defn-loc :list)]
        (let [params-loc (z/down list-loc)]
          (parse-params params-loc context scoped))
        (when-let [next-list (z/find-next-tag list-loc :list)]
          (recur next-list)))
      (let [params-loc (z/find-tag defn-loc :vector)]
        (parse-params params-loc context scoped)))))

(comment
  '[clojure.core/with-open
    clojure.core/if-some
    clojure.core/if-let
    clojure.core/when-let
    clojure.core/when-some
    clojure.core/when-first
    clojure.core/with-open
    clojure.core/dotimes
    clojure.core/letfn
    clojure.core/loop
    clojure.core/with-local-vars
    clojure.core/as->
    ])

(def ^:dynamic *sexpr-handlers*
  {'clojure.core/ns handle-ns
   'clojure.core/defn handle-defn
   'clojure.core/defn- handle-defn
   'clojure.core/fn handle-fn
   'clojure.core/declare handle-def
   'clojure.core/def handle-def
   'clojure.core/defonce handle-def
   'clojure.core/defmacro handle-defmacro
   'clojure.core/let handle-let
   'clojure.core/when-let handle-let
   'clojure.core/when-some handle-let
   'clojure.core/with-open handle-let
   'clojure.core/loop handle-let
   'clojure.core/for handle-let
   'clojure.core/doseq handle-let
   'clojure.core/comment handle-comment})

(defn handle-sexpr [loc context scoped]
  (let [op-loc (some-> loc (zm/down))]
    (cond
      (and op-loc (symbol? (z/sexpr op-loc)))
      (let [qualified-op (:sym (qualify-ident (z/sexpr op-loc) @context scoped))
            handler (get *sexpr-handlers* qualified-op)]
        (add-reference context scoped (z/node op-loc) {})
        (if handler
          (handler op-loc loc context scoped)
          (handle-rest (zm/right op-loc) context scoped)))
      op-loc
      (handle-rest op-loc context scoped))))

(defn find-references* [loc context scoped]
  (loop [loc loc
         scoped scoped]
    (if (or (not loc) (zm/end? loc))
      @context

      (let [tag (z/tag loc)]
        (cond
          (#{:quote :uneval} tag)
          (recur (skip-over loc) scoped)

          (= :list tag)
          (do
            (handle-sexpr loc context scoped)
            (recur (skip-over loc) scoped))

          (and (= :token tag) (symbol? (z/sexpr loc)))
          (do
            (add-reference context scoped (z/node loc) {})
            (recur (skip-over loc) scoped))

          :else
          (recur (zm/next loc) scoped))))))

(defn find-references [code]
  (-> code
      (z/of-string)
      (zm/up)
      (find-references* (volatile! default-env) {})))

;; From rewrite-cljs
(defn in-range? [{:keys [row col end-row end-col]} {r :row c :col}]
  (and (>= r row)
       (<= r end-row)
       (if (= r row) (>= c col) true)
       (if (= r end-row) (<= c end-col) true)))

;; From rewrite-cljs
(defn find-last-by-pos
  "Find last node (if more than one node) that is in range of pos and
  satisfying the given predicate depth first from initial zipper
  location."
  ([zloc pos] (find-last-by-pos zloc pos (constantly true)))
  ([zloc pos p?]
   (->> zloc
        (iterate z/next)
        (take-while identity)
        (take-while (complement z/end?))
        (filter #(and (p? %)
                      (in-range? (-> % z/node meta) pos)))
        last)))

(defn loc-at-pos [code row col]
  (-> code
      (z/of-string)
      (find-last-by-pos {:row row :col col :end-row row :end-col col})))

(comment
  (let [code (string/join "\n"
                          ["(ns thinger.foo"
                           "  (:refer-clojure :exclude [update])"
                           "  (:require"
                           "    [thinger [my.bun :as bun]"
                           "             [bung.bong :as bb :refer [bing byng]]]))"
                           "(comment foo)"
                           "(let [x 1] (inc x))"
                           "(def x 1)"
                           "(defn y [y] (y x))"
                           "(inc x)"
                           "(bun/foo)"
                           "(bing)"])]
    #_(find-references code)
    (z/sexpr (loc-at-pos code 1 2))
    )

  (as-> (find-references "(defn x ([{:keys [a]} {}] a))") $
      (dissoc $ :refers)
      (:usages $)
      (map (juxt :sym :tags) $))

  (dissoc (find-references
)
          :refers))
