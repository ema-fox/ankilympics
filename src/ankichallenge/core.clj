(ns ankichallenge.core
  (:use [noir.server :only [start]]
        [clojure.data.json :only [write-str]]
        noir.core
        hiccup.core)
  (:import [java.io FileNotFoundException]))

(defn write-to [r path]
  (let [a (agent false)]
    (add-watch r path
               (fn [_ _ _ _]
                 (send a (fn [pending]
                           (when-not pending
                             (.start (Thread. (fn []
                                                (Thread/sleep (* 10 60 1000))
                                                (send-off a (fn [_]
                                                              (spit path @r)
                                                              false)))))
                             true))))))
  r)

(defn persref-fn [path initf]
  (write-to (ref (try
                   (read-string (slurp path))
                   (catch FileNotFoundException e
                     (initf))))
            path))

(defmacro persref [path init]
  `(persref-fn ~path (fn [] ~init)))

(defonce data (persref "data.clj"
                       {:points {}
                        :challengers {}
                        :init-points {}}))

(defn relps [{:keys [points init-points]}]
  (merge-with - points init-points))

(defn show-points []
  (str (:points @data)))

(defn update-points [name amount]
  (dosync
   (let [oldrelps (relps @data)]
     (alter data update-in [:init-points name] #(or % amount))
     (alter data assoc-in [:points name] amount)
     (if-let [before (get oldrelps name)]
       (let [caught (map first
                         (filter #(and (< before (second %))
                                       (<= (second %)
                                           (- amount (get (:init-points @data)
                                                          name))))
                                 oldrelps))]
         (alter data update-in [:challengers]
                #(merge-with + % (into {} (for [cname caught]
                                            [(sort [name cname]) 1])))))))))

(defpage [:post "/points"] {:keys [name amount]}
  (update-points name (Integer. amount))
  (write-str {:msg (show-points)}))

(defpage "/" []
  (html
   [:h1 "Ankichallenge high score"]
   [:div (for [[name points]
               (sort-by second > (relps @data))]
           [:div (str name ": " points)])]))

(defn -main [port]
  (start (Integer. port)))

