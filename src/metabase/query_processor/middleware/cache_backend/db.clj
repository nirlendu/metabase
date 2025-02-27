(ns metabase.query-processor.middleware.cache-backend.db
  (:require
   [java-time.api :as t]
   [metabase.db :as mdb]
   [metabase.db.query :as mdb.query]
   [metabase.premium-features.core :refer [defenterprise]]
   [metabase.query-processor.middleware.cache-backend.interface :as i]
   [metabase.util.date-2 :as u.date]
   [metabase.util.encryption :as encryption]
   [metabase.util.log :as log]
   [toucan2.connection :as t2.connection]
   ^{:clj-kondo/ignore [:discouraged-namespace]}
   [toucan2.core :as t2])
  (:import
   (java.sql Connection PreparedStatement ResultSet Types)))

(set! *warn-on-reflection* true)

(defn- ms-ago [n]
  (u.date/add (t/offset-date-time) :millisecond (- n)))

(defn- seconds-ago [n]
  (ms-ago (long (* 1000 n))))

;; this is memoized for a given application DB so we can deliver cached results EXTRA FAST and not have to spend an
;; extra microsecond compiling the same exact query every time. :shrug:
;;
;; Since application DB can change at run time (during tests) it's not just a plain delay
(let [f (memoize (fn [_db-type]
                   (first (mdb.query/compile {:select   [:results]
                                              :from     [:query_cache]
                                              :where    [:and
                                                         [:= :query_hash [:raw "?"]]
                                                         [:>= :updated_at [:raw "?"]]]
                                              :order-by [[:updated_at :desc]]
                                              :limit    [:inline 1]}))))]
  (defn- cached-results-query-sql []
    (f (mdb/db-type))))

(defn prepare-statement
  "Create a prepared statement to query cache"
  ^PreparedStatement [^Connection conn query-hash updated-at]
  (let [stmt (.prepareStatement conn ^String (cached-results-query-sql)
                                ResultSet/TYPE_FORWARD_ONLY
                                ResultSet/CONCUR_READ_ONLY
                                ResultSet/CLOSE_CURSORS_AT_COMMIT)]
    (try
      (doto stmt
        (.setFetchDirection ResultSet/FETCH_FORWARD)
        (.setBytes 1 query-hash)
        (.setObject 2 updated-at Types/TIMESTAMP_WITH_TIMEZONE)
        (.setMaxRows 1))
      (catch Throwable e
        (log/error e "Error preparing statement to fetch cached query results")
        (.close stmt)
        (throw e)))))

(defn fetch-cache-stmt-ttl
  "Make a prepared statement for :ttl caching strategy"
  ^PreparedStatement [strategy query-hash ^Connection conn]
  (if-not (:avg-execution-ms strategy)
    (log/debugf "Caching strategy %s needs :avg-execution-ms to work" (pr-str strategy))
    (let [max-age-ms     (* (:multiplier strategy)
                            (:avg-execution-ms strategy))
          invalidated-at (t/max (ms-ago max-age-ms) (:invalidated-at strategy))]
      (prepare-statement conn query-hash invalidated-at))))

(defenterprise fetch-cache-stmt
  "Returns prepared statement for a given strategy and query hash - on EE. Returns `::oss` on OSS."
  metabase-enterprise.cache.strategies
  [strategy hash conn]
  (when (= :ttl (:type strategy))
    (fetch-cache-stmt-ttl strategy hash conn)))

(defn- cached-results [query-hash strategy respond]
  ;; VERY IMPORTANT! Open up a connection (which internally binds [[toucan2.connection/*current-connectable*]] so it
  ;; will get reused elsewhere for the duration of results reduction, otherwise we can potentially end up deadlocking if
  ;; we need to acquire another connection for one reason or another, such as recording QueryExecutions
  (t2/with-connection [conn]
    (when-let [stmt (fetch-cache-stmt strategy query-hash conn)]
      (with-open [stmt ^PreparedStatement stmt
                  rs   (.executeQuery stmt)]
        (assert (= t2.connection/*current-connectable* conn))
        (if-not (.next rs)
          (respond nil)
          (with-open [is (encryption/maybe-decrypt-stream (.getBinaryStream rs 1))]
            (respond is)))))))

(defn- purge-old-cache-entries!
  "Delete any cache entries that are older than the global max age `max-cache-entry-age-seconds` (currently 3 months)."
  [max-age-seconds]
  {:pre [(number? max-age-seconds)]}
  (log/trace "Purging old cache entries.")
  (try
    (t2/delete! (t2/table-name :model/QueryCache)
                :updated_at [:<= (seconds-ago max-age-seconds)])
    (catch Throwable e
      (log/error e "Error purging old cache entries")))
  nil)

(defn- save-results!
  "Save the `results` of query with `query-hash`, updating an existing QueryCache entry if one already exists, otherwise
  creating a new entry."
  [^bytes query-hash ^bytes results]
  (log/debugf "Caching results for query with hash %s." (pr-str (i/short-hex-hash query-hash)))
  (let [final-results (encryption/maybe-encrypt-for-stream results)
        timestamp     (t/offset-date-time)]
    (try
      (or (pos? (t2/update! :model/QueryCache {:query_hash query-hash}
                            {:updated_at timestamp
                             :results    final-results}))
          (first (t2/insert-returning-instances! :model/QueryCache
                                                 :updated_at timestamp
                                                 :query_hash query-hash
                                                 :results final-results)))
      (catch Throwable e
        (log/error e "Error saving query results to cache.")))
    nil))

(defmethod i/cache-backend :db
  [_]
  (reify i/CacheBackend
    (cached-results [_ query-hash strategy respond]
      (cached-results query-hash strategy respond))

    (save-results! [_ query-hash is]
      (save-results! query-hash is)
      nil)

    (purge-old-entries! [_ max-age-seconds]
      (purge-old-cache-entries! max-age-seconds))))
