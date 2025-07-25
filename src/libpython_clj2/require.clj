(ns libpython-clj2.require
  "Namespace implementing requiring python modules as Clojure namespaces.  This works via
  scanning the module for metadata and dynamically building the Clojure namespace."
  (:refer-clojure :exclude [fn? doc])
  (:require [libpython-clj2.python :as py]
            [libpython-clj2.metadata :as pymeta]
            [clojure.datafy :refer [datafy nav]]
            [clojure.tools.logging :as log]
            [clojure.core.protocols :as clj-proto])
  (:import (java.io File)))


(defn- parse-flags
  "FSM style parser for flags.  Designed to support both
  unary style flags aka '[foo :reload] and
  boolean flags '[foo :reload true] to support Clojure
  style 'require syntax.  Possibly overengineered."
  [supported-flags reqs]
  ;; Ensure we error out when flags passed in are mistyped.
  ;; First attempt is to filter keywords and make sure any keywords are
  ;; in supported-flags
  (let [total-flags (set (concat supported-flags [:as :refer :exclude
                                                  :* :all :bind-ns :path]))]
    (when-let [missing-flags (->> reqs
                                  (filter #(and (not (total-flags %))
                                                (keyword? %)))
                                  seq)]
      (throw (Exception. (format "Unsupported flags: %s"
                                 (set missing-flags))))))
  ;;Loop through reqs.  If a keyword is found and it is a supported flag,
  ;;see if the next thing is a boolean with a default to true.
  ;;If the flag is enabled (as false could be passed in), conj (or disj) to flag set
  ;;Return reqs minus flags and booleans.
  (loop [reqs reqs
         retval-reqs []
         retval-flags #{}]
    (if (seq reqs)
      (let [next-item (first reqs)
            reqs (rest reqs)
            [bool-flag reqs]
            (if (and (supported-flags next-item)
                     (boolean? (first reqs)))
              [(first reqs) (rest reqs)]
              [true reqs])
            retval-flags (if (supported-flags next-item)
                           (if bool-flag
                             (conj retval-flags next-item)
                             (disj retval-flags next-item))
                           retval-flags)
            retval-reqs (if (not (supported-flags next-item))
                          (conj retval-reqs next-item)
                          retval-reqs)]
        (recur reqs retval-reqs retval-flags))
      retval-flags)))


(defn- extract-refer-symbols
  [{:keys [refer this-module]} public-data]
  (cond
    ;; include everything into the current namespace,
    ;; ignore __all__ directive
    (or (refer :all)
        (and (not (py/has-attr? this-module "__all__"))
             (refer :*)))
    (keys public-data)
    ;; only include that specfied by __all__ attribute
    ;; of python module if specified, else same as :all
    (refer :*)
    (->> (py/get-attr this-module "__all__")
         (map (fn [item-name]
                (let [item-sym (symbol item-name)]
                  (when (contains? public-data item-sym)
                    item-sym))))
         (remove nil?))
    ;; [.. :refer [..]] behavior
    :else
    (do
      (when-let [missing (->> refer
                              (remove (partial contains? public-data))
                              seq)]
        (throw (Exception.
                (format "'refer' symbols not found: %s"
                        (vec missing)))))
      refer)))


(defn- do-require-python
  [reqs-vec]
  (let [[module-name & etc] reqs-vec
        supported-flags     #{:reload :no-arglists :bind-ns}
        flags               (parse-flags supported-flags etc)
        etc                 (->> etc
                                 (remove supported-flags)
                                 (remove boolean?))
        _                   (when-not (= 0 (rem (count etc) 2))
                              (throw (Exception. "Must have even number of entries")))
        etc                 (->> etc (partition-all 2)
                                 (map vec)
                                 (into {}))
        reload?             (:reload flags)
        no-arglists?        (:no-arglists flags)
        bind-ns?            (:bind-ns flags)
        alias-name          (:as etc)
        path                (:path etc)
        exclude             (into #{} (:exclude etc))
        refer-data          (cond
                              (= :all (:refer etc)) #{:all}
                              (= :* (:refer etc))   #{:*}
                              :else                 (into #{} (:refer etc)))
        pyobj               (if path
                              (let [cwd (pymeta/py-getcwd)]
                                (try
                                  (pymeta/py-chdir path)
                                  (py/path->py-obj (str module-name) :reload? reload?)
                                  (finally
                                    (pymeta/py-chdir cwd))))
                              (py/path->py-obj (str module-name) :reload? reload?))
        existing-py-ns?     (find-ns module-name)]
    (create-ns module-name)

    (when bind-ns?
      (let [import-name (or  (not-empty (str alias-name))
                             (str module-name))
            ns-dots (re-find #"[.]" import-name)]
        (when (not (zero? (count ns-dots)))
          (throw (Exception. (str "Cannot have periods in module/class"
                                  "name. Please :alias "
                                  import-name
                                  " to something without periods."))))
        (intern
         (symbol (str *ns*))
         (with-meta (symbol import-name)
           {:file (pymeta/find-file pyobj)
            :line 1})
         pyobj)))

    (when (or (not existing-py-ns?) reload?)
      (pymeta/apply-static-metadata-to-namespace! module-name (datafy pyobj)
                                                  :no-arglists? no-arglists?))
    (when-let [refer-symbols (->> (extract-refer-symbols {:refer       refer-data
                                                          :this-module pyobj}
                                                         (ns-publics
                                                          (find-ns module-name)))
                                  seq)]
      (refer module-name :exclude exclude :only refer-symbols))
    (when alias-name
      (alias alias-name module-name))))


(defn ^:private req-transform
  ([req]
   (if (symbol? req)
     req
     (let [base (first req)
           reqs (rest req)]
       (map (partial req-transform base) reqs))))
  ([prefix req]
   (cond
     (and  (symbol? req)
           (clojure.string/includes? (name req) "."))
     (throw (Exception.
             (str "After removing prefix list, requirements cannot "
                  "contain periods. Please remove periods from " req)))
     (symbol? req)
     (symbol (str prefix "." req))
     :else
     (let [base   (first req)
           reqs   (rest req)
           alias? (reduce (fn [res [a b]]
                            (cond
                              (not b)   nil
                              (= :as a) (reduced b)
                              :else     nil))
                          nil
                          (partition 2 1 reqs))]
       (if alias?
         (into [(symbol (str prefix "." base))] reqs)
         (into [(req-transform prefix base)] reqs))))))



(defn require-python
  "## Basic usage ##

   (require-python 'math)
   (math/sin 1.0) ;;=> 0.8414709848078965

   (require-python '[math :as maaaath])

   (maaaath/sin 1.0) ;;=> 0.8414709848078965

   (require-python 'math 'csv)
   (require-python '[math :as pymath] 'csv))
   (require-python '[math :as pymath] '[csv :as py-csv])
   (require-python 'concurrent.futures)
   (require-python '[concurrent.futures :as fs])
   (require-python '(concurrent [futures :as fs]))

   (require-python '[requests :refer [get post]])

   (requests/get \"https//www.google.com\") ;;=>  <Response [200]>
   (get \"https//www.google.com\") ;;=>  <Response [200]>

   In some cases we may generate invalid arglists metadata for the clojure compiler.
   In those cases we have a flag, :no-arglists that will disable adding arglists to
   the generated metadata for the vars.  Use the reload flag below if you need to
   force reload a namespace where invalid arglists have been generated.

   (require-python '[numpy :refer [linspace] :no-arglists :as np])

   If you would like to bind the Python module to the namespace, use
   the :bind-ns flag.

   (require-python '[requests :bind-ns true]) or
   (require-python '[requests :bind-ns])

   ## Use with custom modules ##

   For use with a custom namespace foo.py while developing, you can
   use:

   (require-python '[foo :reload])

   NOTE: unless you specify the :reload flag,
     ..: the module will NOT reload.  If the :reload flag is set,
     ..: the behavior mimics importlib.reload

   ## Setting up classpath for custom modules ##

   Note: you may need to setup your PYTHONPATH correctly.
   **WARNING**: This is very handy for local REPL development,
            ..: if you are going to AOT classes,
            ..: refer to the documentation on codegen
            ..: or your AOT compilation will fail.
   If your foo.py lives at /path/to/foodir/foo.py, the easiest
   way to do it is:

   (require-python :from \"/path/to/foodir\"
                   'foo) ;; or
   (require-python \"/path/to/foodir\"
                   'foo) ;; or
   (require-python {:from \"/path/to/foodir\"}
                   'foo)

   as you prefer.

   Additionally, if you want to keep the namespacing as you have
   it in Python, you may prefer to use a relative import
   starting from a location of your choosing. If your
   os.getcwd() => /some/path/foo,
   and your directory structure looks something like:

   /some $ tree
   .
   └── path
       ├── baz
       │   └── quux.py
       └── foo
           └── bar.py


   (require-python :from \"path\"
                   '[baz.quux :as quux]
                   :from \"path/foo\"
                   'bar)

   is perfectly acceptable. It probably makes the most
   sense to keep you style consistent, but you can mix
   and match as you see fit between <path>, :from <path>,
   and {:from <path>}.  <path> can either be a file or a
   directory.  If it is a file, the Python path will be
   set to the directory containing that file.

   You may also stack several require-pythons under one path:

   (require-python {:from \"dir-a\"}
                   'a
                   'b
                   'c
                   {:from \"dir-b\"}
                   'e.f
                   'g
                   {:from \"dir-c}
                   'hi.there)

   Other options more in keeping with traditional PYTHONPATH
   management include:

   (require-python 'sys)
   (py/call-attr (py/get-attr sys \"path\")
                 \"append\"
                 \"/path/to/foodir\")

   Another option is

   (require-python 'os)
   (os/chdir \"/path/to/foodir\")


   ## prefix lists ##

   For convenience, if you are loading multiple Python modules
   with the same prefix, you can use the following notation:

   (require-python '(a b c))
   is equivalent to
   (require-python 'a.b 'a.c)

   (require-python '(foo [bar :as baz :refer [qux]]))
   is equivalent to
   (require-python '[foo.bar :as baz :refer [qux]])

   (require-python '(foo [bar :as baz :refer [qux]] buster))
   (require-python '[foo.bar :as baz :refer [qux]] 'foo.buster))

   ## For library developers ##

   If you want to intern all symbols to your current namespace,
   you can do the following --

   (require-python '[math :refer :all])

   However, if you only want to use
   those things designated by the module under the __all__ attribute,
   you can do

   (require-python '[operators :refer :*])"
  ([req]
   (py/with-gil
     (cond
       (list? req) ;; prefix list
       (let [prefix-lists (req-transform req)]
         (doseq [req prefix-lists] (require-python req)))
       (symbol? req)
       (require-python (vector req))
       (vector? req)
       (do-require-python req)
       :else
       (throw (Exception. "Invalid argument: %s" req))))
   :ok)
  ([req & reqs]
   (cond (and (map? req)
              (contains? req :from)
              (seq reqs))
         (apply require-python (:from req) reqs)
         (and (keyword? req)
              (= :from req)
              (string? (first reqs)))
         (apply require-python (first reqs) (rest reqs))
         (and (string? req)
              (.isFile (File. req)))
         (let [file (File. req)
               cwd (pymeta/py-getcwd)]
           (apply require-python (str (.getParent file)) reqs))
         (and (string? req)
              (.isDirectory (File. req)))
         (let [cwd (pymeta/py-getcwd)]
           (try
             (pymeta/py-chdir req)
             (apply require-python reqs)
             (finally
               (pymeta/py-chdir cwd))))
         :else
         (do
           (require-python req)
           (when (not-empty reqs)
             (apply require-python reqs))))
   :ok))


(defn import-python
  "Loads python, python.list, python.dict, python.set, python.tuple,
  and python.frozenset."
  []
  (require-python
   '(builtins
     [list :as python.list]
     [dict :as python.dict]
     [set :as python.set]
     [tuple :as python.tuple]
     [frozenset :as python.frozenset]
     [str :as python.str])
   '[builtins :as python])
  :ok)


(def ^:private builtins (py/import-module "builtins"))


(def ^:private pytype   (comp symbol str (py/get-attr builtins "type")))


(defmulti ^:no-doc pydafy
  "Turn a Python object into Clojure data.  Metadata of Clojure
     data will automatically be merged with nav protocol.

     Extend this method to convert a custom Python object into Clojure data.
     Extend pynav if you would like to nav the resulting Clojure data.

     Note: we don't have a way yet to use a 'python type' as a 'jvm class',
     so you need to extend with the symbol of the object name. I know it's
     an ugly hack. Sorry. See examples in libpython-clj.require."
  pytype)

(defmulti ^:no-doc pynav
  "Nav data from a Python object.

     Extend this method to nav a custom Python object into Clojure data.
     Extend pydafy if you would like to datafy a Python object.

     Note: we don't have a way yet to use a 'python type' as a 'jvm class',
     so you need to extend with the symbol of the object name. I know it's
     an ugly hack. Sorry. See examples in libpython-clj.require."
  (fn [coll k v] (pytype coll)))


(defmethod pydafy :default [x]
  (if (pymeta/pyclass? x)
    (pymeta/datafy-module-or-class x)
    (throw (ex-info (str "datafy not implemented for " (pytype x))
                    {:type (pytype x)}))))


(defmethod pynav :default [x]
  (if (pymeta/pyclass? x)
    (pymeta/nav-module x)
    (throw (ex-info (str "nav not implemented for " (pytype x))
                    {:type (pytype x)}))))


(defmethod pydafy 'builtins.module [m]
  (pymeta/datafy-module-or-class m))


(defmethod pynav 'builtins.module [coll k v]
  (pymeta/nav-module coll k v))


(defmethod pydafy 'builtins.dict [x]
  (py/->jvm x))


(defn ^:private py-datafy [item]
  (let [res (pydafy item)
        m   (meta res)]
    (try
      (with-meta
        res
        (merge
         {'clj-proto/nav pynav}
         m))
      (catch ClassCastException _
        ;; presumably metadata doesn't work for this type
        res))))


(defn ^:private py-nav [coll k v]
  (let [res (pynav coll k v)
        m   (meta res)]
    (try
      (with-meta
        res
        (merge
         {'clj-proto/datafy pydafy}
         m))
      (catch ClassCastException _
        ;; presumably metadata doesn't work for this type
        res))))


(defmacro ^:no-doc pydatafy [& types]
  ;; credit: Tom Spoon
  ;; https://clojurians.zulipchat.com/#narrow/stream/215609-libpython-clj-dev/topic/feature-requests/near/187056819
  `(do ~@(for [t types]
           `(extend-type ~t
              clj-proto/Datafiable
              (datafy [item#] (py-datafy item#))
              clj-proto/Navigable
              (nav [coll# k# v#] (py-nav coll# k# v#))))))
