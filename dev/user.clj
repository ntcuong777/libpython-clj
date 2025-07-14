(ns user
  (:require [libpython-clj2.python :as py]))

(def init-torch-str "
try:
  import torch
  torch.ones(1)
  torch.nn.Linear(5, 2)
except ImportError:
  pass")

(py/initialize!)
(py/run-simple-string init-torch-str)
(println "Initialized Python!")
