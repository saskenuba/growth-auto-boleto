(ns cli.io
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.pprint :refer [cl-format]]
            [clojure.string :as str]
            [clojure.set :as set]))

(def default-filename
  "resources/produtos.csv")

(defn read-or-create-state-file
  "Reads status file."
  [file-path]
  (cond->> file-path
    (not (fs/exists? file-path)) (fs/create-file)
    file-path                    (fs/read-all-lines)))

(defn product-state
  [state url]
  {url {:state state}})

(defn state-file->product-state
  "Receives lines directly from `read-or-create-status` and transforms into a map
  we can use to query for products."
  [lines]
  (into {}
        (map (fn [line]
               (let [[url state] (str/split line #" ")]
                 (product-state state url))) lines)))

(defn load-state-from-file
  []
  (->> (read-or-create-state-file default-filename)
       (state-file->product-state)
       (filter (fn [[_ v]] (= (:state v) ":pending")))
       (into {})))

(defn write-status
  "Available statuses are: `:boletado`, `:on-cart` and `:pending`."
  [file-path status coll]
  {:pre [(set/subset? #{status} #{:boletado :on-cart :pending})]}
  (with-open [w (io/writer (str file-path))]
    (doseq [item coll
            :let [line (cl-format nil "~A ~A\n" item status)]]
      (.write w line))))

(def write-pending-default
  (partial write-status default-filename :pending))

(def write-cart-default
  (partial write-status default-filename :on-cart))

(def write-boletado-default
  (partial write-status default-filename :boletado))
