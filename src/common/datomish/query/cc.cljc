;; Copyright 2016 Mozilla
;;
;; Licensed under the Apache License, Version 2.0 (the "License"); you may not use
;; this file except in compliance with the License. You may obtain a copy of the
;; License at http://www.apache.org/licenses/LICENSE-2.0
;; Unless required by applicable law or agreed to in writing, software distributed
;; under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
;; CONDITIONS OF ANY KIND, either express or implied. See the License for the
;; specific language governing permissions and limitations under the License.

(ns datomish.query.cc
  (:require
     [datomish.query.source
      :refer [attribute-in-source
              constant-in-source]]
     [datomish.util :as util #?(:cljs :refer-macros :clj :refer) [raise raise-str cond-let]]
     [honeysql.core :as sql]
     [datascript.parser :as dp
      #?@(:cljs
            [:refer
             [
              Constant
              Placeholder
              Variable
              ]])])
  #?(:clj
       (:import
          [datascript.parser
           Constant
           Placeholder
           Variable
           ])))


;; A ConjoiningClauses (CC) is a collection of clauses that are combined with JOIN.
;; The topmost form in a query is a ConjoiningClauses.
;;
;;---------------------------------------------------------------------------------------
;; Done:
;; - Ordinary pattern clauses turn into FROM parts and WHERE parts using :=.
;; - Predicate clauses turn into the same, but with other functions.
;; - `not` turns into NOT EXISTS with WHERE clauses inside the subquery to
;;   bind it to the outer variables, or adds simple WHERE clauses to the outer
;;   clause.
;; - `not-join` is similar, but with explicit binding.
;; - `or` turns into a collection of UNIONs inside a subquery, or a simple
;;   alternation.
;;   `or`'s documentation states that all clauses must include the same vars,
;;   but that's an over-simplification: all clauses must refer to the external
;;   unification vars.
;;   The entire UNION-set is JOINed to any surrounding expressions per the `rule-vars`
;;   clause, or the intersection of the vars in the two sides of the JOIN.
;;
;; Not yet done:
;; - Function clauses with bindings turn into:
;;   * Subqueries. Perhaps less efficient? Certainly clearer.
;;   * Projection expressions, if only used for output.
;;   * Inline expressions?
;;---------------------------------------------------------------------------------------
;;
;; `from` is a list of [source alias] pairs, suitable for passing to honeysql.
;; `bindings` is a map from var to qualified columns.
;; `known-types` is a map from var to type keyword (e.g., :db.type/ref)
;; `extracted-types` is a mapping, similar to `bindings`, but used to pull
;; type tags out of the store at runtime.
;; `wheres` is a list of fragments that can be joined by `:and`.
(defrecord ConjoiningClauses
           [source
            from              ; [[:datoms 'datoms123]]
            external-bindings ; {?var0 (sql/param :foobar)}
            bindings          ; {?var1 [:datoms123.v]}
            known-types       ; {?var1 :db.type/integer}
            extracted-types   ; {?var2 :datoms123.value_type_tag}
            wheres            ; [[:= :datoms123.v 15]]
            ctes              ; {:name {:select …}}
            ])

(defn bind-column-to-var [cc variable table position]
  (let [var (:symbol variable)
        col (sql/qualify table (name position))
        bound (util/append-in cc [:bindings var] col)]
    (if (or (not (= position :v))
            (contains? (:known-types cc) var)
            (contains? (:extracted-types cc) var))
      ;; Type known; no need to accumulate a type-binding.
      bound
      (let [tag-col (sql/qualify table :value_type_tag)]
        (assoc-in bound [:extracted-types var] tag-col)))))

(defn constrain-column-to-constant [cc table position value]
  (let [col (sql/qualify table (name position))]
    (util/append-in cc
                    [:wheres]
                    [:= col (if (= :a position)
                              (attribute-in-source (:source cc) value)
                              (constant-in-source (:source cc) value))])))

(defprotocol ITypeTagged (->tag-codes [x]))

(extend-protocol ITypeTagged
  #?@(:cljs
      [string               (->tag-codes [x] #{4 10 11 12})
       Keyword              (->tag-codes [x] #{13})   ; TODO: what about idents?
       boolean              (->tag-codes [x] #{1})
       number               (->tag-codes [x]
                              (if (integer? x)
                                #{0 4 5}     ; Could be a ref or a number or a date.
                                #{4 5}))])   ; Can't be a ref.
  #?@(:clj
      [String               (->tag-codes [x] #{10})
       clojure.lang.Keyword (->tag-codes [x] #{13})   ; TODO: what about idents?
       Boolean              (->tag-codes [x] #{1})
       Integer              (->tag-codes [x] #{0 5})  ; Could be a ref or a number.
       Long                 (->tag-codes [x] #{0 5})  ; Could be a ref or a number.
       Float                (->tag-codes [x] #{5})
       Double               (->tag-codes [x] #{5})
       java.util.UUID       (->tag-codes [x] #{11})
       java.util.Date       (->tag-codes [x] #{4})
       java.net.URI         (->tag-codes [x] #{12})]))

(defn constrain-value-column-to-constant
  "Constrain a `v` column. Note that this can contribute *two*
   constraints: one for the column itself, and one for the type tag.
   We don't need to do this if the attribute is known and thus
   constrains the type."
  [cc table-alias value]
  (let [possible-type-codes (->tag-codes value)
        aliased (sql/qualify table-alias (name :value_type_tag))
        clauses (map
                  (fn [code] [:= aliased code])
                  possible-type-codes)]
    (util/concat-in cc [:wheres]
                    ;; Type checks then value checks.
                    [(case (count clauses)
                       0 (raise-str "Unexpected number of clauses.")
                       1 (first clauses)
                       (cons :or clauses))
                     [:= (sql/qualify table-alias (name :v))
                         (constant-in-source (:source cc) value)]])))

(defn combine-known-types [left right]
  (merge-with (fn [lt rt]
                (if (= lt rt)
                  lt
                  (raise "Incompatible types: " lt " != " rt {:types [lt rt]})))
              left right))

(defn augment-cc [cc from bindings known-types extracted-types wheres]
  (assoc cc
         :from (concat (:from cc) from)
         :bindings (merge-with concat (:bindings cc) bindings)
         :known-types (combine-known-types (:known-types cc) known-types)
         :extracted-types (merge (:extracted-types cc) extracted-types)
         :wheres (concat (:wheres cc) wheres)))

(defn merge-ccs [left right]
  (augment-cc left
              (:from right)
              (:bindings right)
              (:known-types right)
              (:extracted-types right)
              (:wheres right)))

(defn- bindings->where
  "Take a bindings map like
    {?foo [:datoms12.e :datoms13.v :datoms14.e]}
  and produce a list of constraints expression like
    [[:= :datoms12.e :datoms13.v] [:= :datoms12.e :datoms14.e]]

  TODO: experiment; it might be the case that producing more
  pairwise equalities we get better or worse performance."
  [bindings]
  (mapcat (fn [[_ vs]]
            (when (> (count vs) 1)
              (let [root (first vs)]
                (map (fn [v] [:= root v]) (rest vs)))))
          bindings))

;; This is so we can link clauses to the outside world.
;; Note that we sort the variable list to achieve consistent ordering between
;; Clojure and ClojureScript, yielding sane tests.
(defn- impose-external-bindings [cc]
  (if (empty? (:external-bindings cc))
    cc
    (let [ours (:bindings cc)
          theirs (:external-bindings cc)
          vars (clojure.set/intersection (set (keys theirs)) (set (keys ours)))]
      (util/concat-in
        cc [:wheres]
        (map
          (fn [v]
            (let [external (first (v theirs))
                  internal (first (v ours))]
              (assert external)
              (assert internal)
              [:= external internal]))
          (sort vars))))))

(defn expand-where-from-bindings
  "Take the bindings in the CC and contribute
   additional where clauses. Calling this more than
   once will result in duplicate clauses."
  [cc]
  (impose-external-bindings
    (assoc cc :wheres
           ;; Note that the order of clauses here means that cross-pattern var bindings
           ;; come last. That's OK: the SQL engine considers these altogether.
           (concat (:wheres cc)
                   (bindings->where (:bindings cc))))))

(defn binding-for-symbol [cc symbol]
  (let [internal-bindings (symbol (:bindings cc))
        external-bindings (symbol (:external-bindings cc))]
    (or (first internal-bindings)
        (first external-bindings))))

(defn binding-for-symbol-or-throw [cc symbol]
  (or (binding-for-symbol cc symbol)
      (raise-str "No bindings yet for " symbol)))

(defn argument->value
  "Take a value from an argument list and resolve it against the CC.
   Throws if the value can't be resolved (e.g., no binding is established)."
  [cc arg]
  (condp instance? arg
    Placeholder
    (raise-str "Can't use a placeholder in a predicate.")

    Variable
    (binding-for-symbol-or-throw cc (:symbol arg))

    Constant
    (constant-in-source (:source cc) (:value arg))

    (raise-str "Unknown predicate argument " arg)))
