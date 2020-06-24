(ns inferenceql.inference.gpm.column
  "Implementation of a GPM that represents a population of data of the
  same primitive type. For a tabular dataset, this GPM abstracts a Column
  of that dataset. See `inferenceql.inference.gpm/column` for details."
  (:require [inferenceql.inference.gpm.primitive-gpms :as pgpms]
            [inferenceql.inference.primitives :as primitives]
            [inferenceql.inference.utils :as utils]
            [inferenceql.inference.gpm.proto :as gpm.proto]))

(defn crp-weights
  "Given a column and alpha, returns the CRP prior over categories."
  [column alpha]
  (-> (reduce-kv (fn [weights cat-name category]
                   (assoc weights cat-name (-> category :suff-stats :n)))
                 {}
                 (:categories column))
      (assoc :aux alpha)
      (utils/log-normalize)))

(defn generate-category
  "Given a column, generates a category from the column's hyperparameters."
  [column]
  (let [metadata (:metadata column)
        stattype (:stattype column)
        var-name (:var-name column)
        hyperparameters (:hyperparameters column)]
      (pgpms/->pGPM stattype var-name :hyperparameters hyperparameters :options metadata)))

(defn add-category
  "Adds a category under the given symbol name to the specified column."
  [column symb]
  (let [category (generate-category column)]
    (assoc-in column [:categories symb] category)))

(defn update-column
  "Updates a column's categories based on a new view's latent assignments.
  Used in incorporate-column."
  [column latents]
  (let [ys (:y latents)
        categories (into {} (map vector
                                 (vals ys)
                                 (repeat (count ys) (generate-category column))))
        ;; Incorporate the data accordingly, based on the latent spec.
        categories' (reduce (fn [acc [row-id datum]]
                              (if (not (nil? datum))
                                (let [category (get ys row-id)]
                                  (update acc
                                           category
                                           #(gpm.proto/incorporate
                                              %
                                              datum)))
                                  acc))
                             categories
                             (get column :data))]
    (assoc column :categories categories')))

;;;; Functions for CrossCat inference
(defn crosscat-incorporate
  "Incorporate method for CrossCat inference machinery.
  Incorporates `values` with a given `row-id` into the category
  specified by `category-key`."
  [column values category-key row-id]
  (let [x (get values (:var-name column))]
    (if-not (nil? x)
      (-> column
          (update-in [:categories category-key] (fnil #(gpm.proto/incorporate % values)
                                                      (generate-category column)))
          (update-in [:assignments values] (fnil (fn [cats]
                                                   (update cats category-key (fnil inc 0)))
                                                 {}))
          (update :data assoc row-id (get values (:var-name column))))
      column)))

(defn crosscat-unincorporate
  "Unincorporate method for CrossCat inference machinery.
  Unincorporates the values with the associated `row-id` from the category
  specified by `category-key`."
  [column category-key row-id]
  (let [values {(:var-name column) (get-in column [:data row-id])}]
    (-> column
        (update-in [:categories category-key] #(gpm.proto/unincorporate % values))
        (update-in [:assignments values] (fnil (fn [cats]
                                                 (update cats category-key (fnil dec 0)))
                                               {}))
        (update :data dissoc row-id))))

(defrecord Column [var-name stattype categories assignments hyperparameters hyper-grid metadata]
  gpm.proto/GPM
  (logpdf [this targets constraints]
    ;; - If targets and constraints overlap, returns 0 if equal or -Inf if not.
    ;; The procedure for calculating the logpdf of the column is as follows.
    ;; 1) Calculate the unnormalized weights for each category, which is the log of the number
    ;;    of values it contains.
    ;; 2) Normalize the weights by subtracting the log of alpha + the number of values in the Column.
    ;; 3) Add each weight to its respective category logpdf.
    ;; 4) Logsumexp the weighted logpdfs. i.e. (logsumexp (+ weight-0 logpdf-0) ... (+ weight-k logpdf-k))
    (let [column-target {var-name (get targets var-name)}
          column-constraint (get constraints var-name)]
      (cond
        (nil? (get targets var-name)) 0
        (some? column-constraint) (if (= (get column-target var-name) column-constraint) 0 ##-Inf)
        :else (let [weights-lls
                    (reduce-kv (fn [m cat-name category]
                                 (-> m
                                     ;; A category's weight is proportional to how many elements it contains.
                                     (assoc-in [:weights cat-name] (Math/log (-> category :suff-stats :n)))
                                     (assoc-in [:logps cat-name] (gpm.proto/logpdf category column-target {}))))
                               ;; Generate an additional category in the Column, the weight of which is
                               ;; defined by the concentration parameter of the Column, `alpha`.
                               {:weights {:aux (Math/log (get this :alpha 1))}
                                :logps {:aux (gpm.proto/logpdf (generate-category this) column-target {})}}
                               categories)]
                ;; We want to sum probabilities across all categories, but since we are in the log space, we must
                ;; take the logsumexp of the values, which allows us to perform the same calculation while staying
                ;; in the log space (and thus limiting exposure to floating point errors for small exponentiated
                ;; values).
                (utils/logsumexp (vals (merge-with +
                                                   (utils/log-normalize (:weights weights-lls))
                                                   (:logps weights-lls))))))))
  (simulate [this targets constraints n-samples]
    (let [categories' ; We must add an additional category to truly sample from the Column's CRP.
          (assoc categories :aux (generate-category this))
          ;; Generates the CRP weights for the categories.
          crp-prior (->> categories'
                         (reduce-kv (fn [m cat-name category]
                                      (assoc m cat-name (-> category :suff-stats :n)))
                                    {})
                         (utils/log-normalize))]
      ;; Sample a category assignment, then simulate a value from that category.
      ;; It is important to note that each sample is independent, and the underlying Column
      ;; is not at all affected for "sequential" simulations.
      (repeatedly n-samples #(let [category-key (primitives/simulate :log-categorical {:p crp-prior})]
                               (gpm.proto/simulate (get categories' category-key) [var-name] {} 1)))))

  gpm.proto/Incorporate
  (incorporate [this values]
    ;; Sample a category and then incorporate the values into said category.
    (let [weights (crp-weights this (get this :alpha 1))
          category (primitives/simulate :log-categorical {:p weights})
          category (if (= :aux category) (gensym) category)]
      (-> this
          (update-in [:categories category] (fnil #(gpm.proto/incorporate % values)
                                                  (generate-category this)))
          (update-in [:assignments values] (fnil (fn [cats]
                                                   (update cats category (fnil inc 0)))
                                                 {})))))
  (unincorporate [this values]
    ;; Choose one of the categories that `values` belongs to at random,
    ;; and remove the value from the category, removing the empty category
    ;; from the Column, if necessary.
    (let [cats (get-in this [:assignments values] nil)
          cat-remove (rand-nth (keys cats))]
      (-> this
          (update-in [:assignments values cat-remove] (fnil dec 1))
          ;; Remove empty categories, if necessary.
          (update-in [:assignments values] #(if (zero? (get % cat-remove))
                                              (dissoc % cat-remove)
                                              %))
          (update :categories #(let [cat-remove' (gpm.proto/unincorporate (get % cat-remove) values)]
                                 (if (zero? (get-in cat-remove' [:suff-stats :n]))
                                   (dissoc % cat-remove) ; Remove empty category from column.
                                   (assoc % cat-remove cat-remove')))))))

  gpm.proto/Score
  (logpdf-score [this]
    ;; Calculates the logpdf-score by taking the logsumexp of the logpdf-score of the constituent categories,
    ;; weighted by their respective sizes (similar to a CRP, without an additional cluster).
    (let [weights-lls
          (reduce-kv (fn [m cat-name category]
                       (-> m
                           (assoc-in [:weights cat-name] (Math/log (-> category :suff-stats :n)))
                           (assoc-in [:lls cat-name] (gpm.proto/logpdf-score category))))
                     {}
                     categories)]
          (->> (merge-with + (utils/log-normalize (:weights weights-lls)) (:lls weights-lls))
               (vals)
               (utils/logsumexp)))))

(defn construct-column-from-latents
  "Constructor for a Column GPM, given data for the column and latent
  assignments of data to their respective categories. Used in CrossCat inference.

  var-name: the name of the variable contained in the column.
  stattype: the statistical type of the variable contained in the column (e.g. :bernoulli).
  hyperparameters: the hyperparameters of column; these persist across all categories.
  latents: a map of the below structure, used to keep track of row-category assignments,
           as well as category sufficient statistics:
             {:alpha  number                     The concentration parameter for the Column's CRP
              :counts {category-name count}      Maps category name to size of the category. Updated
                                                 incrementally instead of being calculated on the fly.
              :y {row-identifier category-name}  Maps rows to their current category assignment.
  data: the data belonging to the Column. Must either be a map of {row-id datum} or a vector
        of data (that includes nil values).
  options (optional): Information needed in the column; e.g. For a :categorical Column,
                      `options` would contain a list of possible values the variable could take.
  crosscat (optional): Flag to indicate use in a CrossCat model. This affects how data is handled
                       internally, as CrossCat inference relies on unique row identifiers for
                       efficient inference."
  ([var-name stattype hyperparameters latents data]
   (construct-column-from-latents var-name stattype hyperparameters latents data {:options {} :crosscat false}))
  ([var-name stattype hyperparameters latents data {:keys [options crosscat]}]
    (let [ys (:y latents) ; Row-category assignments.
          category-names (keys (:counts latents))
          ;; Add row-identifiers to the data, if not already present.
          data (if (map? data) data (zipmap (range) data))
          metadata (if (= stattype :categorical)
                     (get options var-name)
                     {})
          ;; Generate empty categories, before incorporating data.
          categories (into {} (map vector
                                   category-names
                                   (repeatedly
                                     #(pgpms/->pGPM stattype var-name :hyperparameters hyperparameters :options metadata))))
          ;; Incorporate the data accordingly, based on the latents spec.
          categories' (reduce (fn [acc [row-id datum]]
                                (if (some? datum) ; Only incorporate non-nil values.
                                  (let [category (get ys row-id)]
                                     (update acc
                                             category
                                             #(gpm.proto/incorporate
                                                %
                                                {var-name datum})))
                                   acc))
                               categories
                               data)
          ;; The hyper-grid is defined on initialization because there is no need to update or change it,
          ;; unless you are adding or removing values from the Column. This saves a lot of computation when
          ;; the hyper-grid is used in column hyperparameter inference.
          hyper-grid (pgpms/hyper-grid stattype (remove nil? (vals data)))
          ;; assignments is used to treat values as items in a bag. We need to keep track of how many of each
          ;; value are in each category. An example of its form for a bernoulli variable looks like the below:
          ;;        {{"flip" true} {:cat-0 4       There are four instances of this value in :cat-0.
          ;;                        :cat-1 2}}     There are two instances of this value in :cat-1.
          assignments (reduce-kv (fn [assignments' row-id data]
                                   (let [y (get-in latents [:y row-id])]
                                     (update-in assignments' [{var-name data} y] (fnil inc 0))))
                                 {}
                                 data)
          column (->Column var-name stattype categories' assignments hyperparameters hyper-grid metadata)]
      ;; If this Column is meant to be included in a CrossCat model, then we record the data, otherwise,
      ;; it isn't necessary. This may be deprecated as implementation of higher-level CrossCat modules
      ;; continues.
      (if crosscat
        (assoc column :data data)
        column))))
