(ns safely.examples.etl-load.gen-file
  (:require [clojure.string :as str]
            [safely.core :refer [safely]]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [progrock.core :as pr]))



(def all-words
  (delay
   (safely
    (println "attempting to read: /usr/share/dict/words")
    (str/split-lines (slurp "/usr/share/dict/words"))
    :on-error
    :log-stacktrace false
    :default nil)))



(defn gen-rand-letters [max-size]
  (let [size (inc (rand-int max-size))]
    (->> (repeatedly #(rand-nth "qwertyuiopasdfghjklzxcvbnm1234567890 !@#$%^&*()-QWERTYUIOPASDFGHJKLZXCVBNM"))
         (take size)
         (apply str))))



(defn gen-rand-words [max-size]
  (let [size (inc (rand-int max-size))]
    (loop [words ""]
      (if (>= (count words) size)
        (str/trim words)
        (recur (str words " " (rand-nth @all-words)))))))



(defmulti  gen-rand-value (fn [type _] type))



(defmethod gen-rand-value :string [_ budget]
  (if @all-words (gen-rand-words budget) (gen-rand-letters budget)))



(defmethod gen-rand-value :date [_ _]
  (format "%tFT%<tT.%<tLZ"
   (java.util.Date. (+ (* (rand-int (quot (System/currentTimeMillis) 1000)) 1000)
                       (rand-int 1000)))))



(defmethod gen-rand-value :number [_ _]
  (rand-int (quot (System/currentTimeMillis) 1000)))



(defmethod gen-rand-value :uuid [_ _]
  (str (java.util.UUID/randomUUID)))



(defmethod gen-rand-value :bool [_ _]
  (rand-nth [true false]))



(defn- gen-rand-value-of [types budget]
  (gen-rand-value (rand-nth types) budget))



(defn gen-rand-record
  [{:keys [types total-size] :as opts
    :or {types    [:string :date :number :uuid :bool]
         total-size 1000}}]

  (loop [generated []
         budget    total-size
         num       0]
    (if (<= budget 0)
      (into {} generated)
      (let [new-entry [(str "field-" num) (gen-rand-value-of types budget)]]
        (recur (conj generated new-entry)
               (- budget (count (str new-entry)))
               (inc num))))))



(defn gen-rand-record-with-id
  [{:keys [types total-size] :as opts
    :or {types    [:string :date :number :uuid :bool]
         total-size 1000}}]
  (-> (gen-rand-record opts)
      (assoc "id" (gen-rand-value :uuid 1))))



(defn gen-records
  [file num-recs rec-size]
  (println "Generating" num-recs "random records into:" file)
  (let [pb (volatile! (pr/progress-bar num-recs))]
    (with-open [wrtr (io/writer file)]
      (doseq [record (->> (repeatedly num-recs #(gen-rand-record-with-id {:total-size rec-size}))
                          (map json/generate-string))]
        (.write wrtr (str record \newline))
        (pr/print (vswap! pb pr/tick))))
    (pr/print (vswap! pb pr/done)))
  (println "all done..."))


;;
;; Generating 1Gb file with 1M records of 1K each
;; (gen-records "/tmp/large-file.json" 1000000 1000)
;;
