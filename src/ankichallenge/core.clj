(ns ankichallenge.core
  (:use [noir.server :only [start]]
        [clojure.data.json :only [write-str]]
        noir.core
        hiccup.core
        [hiccup.util :only [escape-html]])
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

(defn relp [{:keys [points init-points]} name]
  (- (get points name) (get init-points name)))

(defn show-points []
  (str (:points @data)))

(defn passed [before after rs]
  (map (fn [f]
         (map first (filter #(f (second %)) rs)))
       [#(= before %)
        #(< before % after)
        #(= % after)]))

(defn update-points [name amount]
  (dosync
   (let [oldrelps (relps @data)]
     (alter data update-in [:init-points name] #(or % amount))
     (alter data assoc-in [:points name] amount)
     (if-let [before (get oldrelps name)]
       (let [after (relp @data name)]
         (when (< before after)
           (let [[_ xs ys] (passed before
                                   after
                                   oldrelps)
                 caught (concat xs ys)]
             (alter data update-in [:challengers name]
                    #(merge-with + % (reduce (fn [chals name]
                                               (assoc chals
                                                 name 1))
                                             {}
                                             caught))))))))))

(defn next-challenger [dt name direction]
  (let [rs (relps dt)]
    (first (sort-by second direction (filter #(direction (get rs name) (second %)) (dissoc rs name))))))

(defn percent-challenger [dt name]
  (if (get-in dt [:points name])
    (if-let [[cname crp] (next-challenger dt name <)]
      (let [[_ foorp] (or (next-challenger dt name >=) [nil 0])]
        (str (int (* (/ (- (relp dt name) foorp) (- crp foorp)) 100))
             "% to " cname))
      "9001% to being the best")))

(defpage [:post "/points"] {:keys [name amount]}
  (let [oldpc (percent-challenger @data name)]
    (update-points name (Integer. amount))
    (write-str {:msg (let [newpc (percent-challenger @data name)]
                       (if (not= oldpc newpc)
                         newpc
                         (str (relp @data name) " points")))})))

(defpage "/" []
  (html
   [:h1 "Ankichallenge high score"]
   [:div (for [[name points]
               (sort-by second > (relps @data))]
           [:div (str (escape-html name) ": " points)])]))

(defn -main [port]
  (start (Integer. port)))
