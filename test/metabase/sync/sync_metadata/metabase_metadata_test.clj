(ns metabase.sync.sync-metadata.metabase-metadata-test
  "Tests for the logic that syncs the `_metabase_metadata` Table."
  (:require
   [clojure.test :refer :all]
   [metabase.sync.sync-metadata.metabase-metadata :as metabase-metadata]
   [metabase.test :as mt]
   [metabase.test.mock.moviedb :as moviedb]
   [metabase.util :as u]
   [toucan2.core :as t2]))

(deftest sync-metabase-metadata-test
  (testing ":Test that the `_metabase_metadata` table can be used to populate values for things like descriptions"
    (letfn [(get-table-and-fields-descriptions [table-or-id]
              (-> (t2/select-one [:model/Table :id :name :description], :id (u/the-id table-or-id))
                  (t2/hydrate :fields)
                  (update :fields #(for [field %]
                                     (select-keys field [:name :description])))
                  mt/boolean-ids-and-timestamps))]
      (mt/with-temp [:model/Database db {:engine ::moviedb/moviedb}]
        ;; manually add in the movies table
        (let [table (first (t2/insert-returning-instances! :model/Table
                                                           :db_id  (u/the-id db)
                                                           :name   "movies"
                                                           :active true))]
          (t2/insert! :model/Field
                      :database_type "BOOL"
                      :base_type     :type/Boolean
                      :table_id      (u/the-id table)
                      :name          "filming")
          (testing "before"
            (is (= {:name        "movies"
                    :description nil
                    :id          true
                    :fields      [{:name "filming", :description nil}]}
                   (get-table-and-fields-descriptions table)))
            (is (nil? (:description (t2/select-one :model/Database :id (u/the-id db))))))
          (metabase-metadata/sync-metabase-metadata! db)
          (testing "after"
            (is (= {:name        "movies"
                    :description "A cinematic adventure."
                    :id          true
                    :fields      [{:name "filming", :description "If the movie is currently being filmed."}]}
                   (get-table-and-fields-descriptions table)))
            (is (= "Information about movies" (:description (t2/select-one :model/Database :id (u/the-id db)))))))))))
