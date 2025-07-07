(ns build
  (:require [clojure.tools.build.api :as b]))

;; 1) your Maven coordinates
(def lib      'clj-python/libpython-clj)
(def version  "2.027-CHRIST-SNAPSHOT")

;; 2) where we'll compile and package
(def class-dir "target/classes")
(def jar-file  (format "target/%s-%s.jar" (name lib) version))

;; 3) compute the “basis” from deps.edn so tools.build can see your deps
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean
  "Delete the entire target directory."
  [_]
  (println "→ cleaning target/")
  (b/delete {:path "target"}))

(defn jar
  "Compile sources & package into a JAR at `target/...jar`."
  [_]
  (clean nil)
  (println "→ writing pom.xml")
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis @basis
                :src-dirs ["src"]})
  (println "→ copying src →" class-dir)
  (b/copy-dir {:src-dirs   ["src"]
               :target-dir class-dir})
  (println "→ building jar →" jar-file)
  (b/jar {:class-dir class-dir
          :jar-file  jar-file}))
