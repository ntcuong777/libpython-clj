(ns libpython-clj2.types
  "Useful record types to make managing manual GIL easier.
  These types are used to represent python objects
  and their interactions with the JVM."
  (:require [libpython-clj2.python.ffi :as py-ffi]
            [libpython-clj2.python :as py]))

(defprotocol PyObjectProtocol
  "Protocol for Python objects.  This protocol is implemented by all python objects
  in the system.  It provides a way to safely access the raw python object and to convert
  it to a jvm object."
  (safe-raw [this]
    "Return the raw python object but wrapped inside a GIL locker to avoid crash.")
  (as-jvm [this] "Convert this object into a jvm object."))

;; (defrecord PyObject [raw-obj]
;;   :doc "A record type that represents a Python object. It wraps a raw Python object
;;   and provides methods to safely access it and convert it to a JVM object.
;;   The `raw-obj` field is the raw Python object pointer, and it is not recommended
;;   to access it directly without using the provided methods. This is more of a
;;   convenience type for the user to work with Python objects safely in the JVM
;;   when turning on manual GIL management.

;;   The raw object can still be accessed directly in automatic GIL management mode (as the hard work
;;   is done by the library). But in manual GIL management mode, you should always use
;;   `safe-raw` to ensure that the GIL is held while accessing the raw object."

;;   PyObjectProtocol
;;   (safe-raw [this]
;;     (with-open [locker (py-ffi/manual-gil-locker)]
;;       (py-ffi/incref-track-pyobject (:raw-obj this))))
;;   (as-jvm [this]
;;     (with-open [locker (py-ffi/manual-gil-locker)]
;;       (py/->jvm (:raw-obj this)))))
