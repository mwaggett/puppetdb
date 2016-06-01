(ns puppetlabs.puppetdb.http.index-test
  (:require [clj-time.core :refer [now]]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.examples :as examples]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.scf.storage :as scf-store]
            [puppetlabs.puppetdb.testutils :refer [dotestseq]]
            [puppetlabs.puppetdb.testutils.db :refer [with-test-db]]
            [puppetlabs.puppetdb.testutils.http
             :refer [deftest-http-app
                     ordered-query-result
                     query-response
                     query-result
                     vector-param
                     with-http-app]]))

(def endpoints [[:v4 "/v4"]])

(deftest-http-app index-queries
  [[version endpoint] endpoints
   method [:get :post]]
  (let [catalog (:basic examples/catalogs)
        facts   {"kernel"          "Linux"
                 "operatingsystem" "Debian"}
        facts1  (assoc facts "fqdn" "host1")
        facts2  (assoc facts "fqdn" "host2")
        facts3  (assoc facts "fqdn" "host3")
        cat1    (assoc catalog :certname "host1")
        cat2    (assoc catalog :certname "host2")
        cat3    (assoc catalog :certname "host3")]
    (scf-store/add-certname! "host1")
    (scf-store/add-certname! "host2")
    (scf-store/add-certname! "host3")
    (scf-store/replace-catalog! cat1 (now))
    (scf-store/replace-catalog! cat2 (now))
    (scf-store/replace-catalog! cat3 (now))
    (scf-store/add-facts! {:certname "host1"
                           :values facts1
                           :timestamp (now)
                           :environment "DEV"
                           :producer_timestamp (now)
                           :producer "foo1"})
    (scf-store/add-facts! {:certname "host2"
                           :values facts2
                           :timestamp (now)
                           :environment "DEV"
                           :producer_timestamp (now)
                           :producer "foo2"})
    (scf-store/add-facts! {:certname "host3"
                           :values facts3
                           :timestamp (now)
                           :environment "DEV"
                           :producer_timestamp (now)
                           :producer "foo3"})
    (scf-store/deactivate-node! "host3")

    (testing "invalid from query"
      (let [{:keys [status body headers]} (query-response method endpoint ["from" "foobar"])]
        (is (re-find #"Invalid entity" body))
        (is (= headers {"Content-Type" http/error-response-content-type
                        "Warning" "The root endpoint is experimental"}))
        (is (= status http/status-bad-request)))

      ;; Ensure we parse anything that looks like AST/JSON as JSON not PQL
      (let [{:keys [status body headers]} (query-response method endpoint "[\"from\",\"foobar\"")]
        (is (= "Malformed JSON for query: [\"from\",\"foobar\"" body))
        (is (= headers {"Content-Type" http/error-response-content-type}))
        (is (= http/status-bad-request status)))

      (let [{:keys [status body headers]} (query-response method endpoint "foobar {}")]
        (is (re-find #"PQL parse error at line 1, column 1" body))
        (is (= headers {"Content-Type" http/error-response-content-type}))
        (is (= status http/status-bad-request))))

    (testing "pagination"
      (testing "with order_by parameter"
        (doseq [query [["from" "nodes"]
                       "nodes {}"
                       "nodes [] {}"]]
          (let [results (ordered-query-result method endpoint query
                                              {:order_by
                                               (vector-param method
                                                             [{"field" "certname"
                                                               "order" "ASC"}])})]
            (is (= "host1" (:certname (first results))))
            (is (= 3 (count results))))))

      (testing "with order_by in query"
        (let [results (ordered-query-result
                        method endpoint ["from" "nodes" ["order_by" ["certname"]]])]
          (is (= "host1" (:certname (first results))))
          (is (= 3 (count results)))))

      (testing "with all options in parameters"
        (doseq [query [["from" "nodes"]
                       "nodes {}"]]
          (let [results (ordered-query-result method endpoint query
                                              {:order_by
                                               (vector-param method
                                                             [{"field" "certname"
                                                               "order" "DESC"}])
                                               :limit 2
                                               :offset 1})]
            (is (= "host2" (:certname (first results))))
            (is (= 2 (count results))))))

      (testing "with all options in query"
        (let [results (ordered-query-result
                        method endpoint
                        ["from" "nodes" ["order_by" [["certname" "desc"]]]
                         ["limit" 2] ["offset" 1]])]
          (is (= "host2" (:certname (first results))))
          (is (= 2 (count results)))))

      (testing "in a subquery"
        (doseq [query [["from" "catalogs"
                        ["in" "certname"
                         ["from" "nodes"
                          ["extract" "certname"]
                          ["limit" 1]
                          ["order_by" ["certname"]]]]]
                       "catalogs { certname in nodes[certname] { limit 1 order by certname } }"]]
          (let [results (ordered-query-result method endpoint query)]
            (is (= 1 (count results)))
            (is (= "host1" (:certname (first results)))))))

      (testing "in a subquery with multiple fields"
        (doseq [query [["from" "facts"
                        ["in" ["certname" "name"]
                          ["from" "fact_contents"
                           ["extract" ["certname" "name"]]
                           ["limit" 1]
                           ["order_by" ["certname"]]]]]
                       "facts { [certname,name] in fact_contents[certname,name] { limit 1 order by certname } }"]]
          (let [results (ordered-query-result method endpoint query)]
            (is (= 1 (count results)))
            (is (= "host1" (:certname (first results))))))))

    (testing "extract parameters"
      (doseq [query [["from" "nodes"
                       ["extract" "certname"
                        ["=" "certname" "host2"]]]
                     "nodes [certname] {certname = 'host2'}"]]
        (let [results (query-result method endpoint query)]
          (is (= results #{{:certname "host2"}})))))

    (testing "nodes"
      (testing "query should return all nodes (including deactivated ones)"
        (doseq [query [["from" "nodes"]
                       "nodes {}"]]
          (is (= (set (mapv :certname (query-result method endpoint query {})))
                 #{"host1" "host2" "host3"}))))

      (testing "broad regexp query should return all nodes"
        (doseq [query [["from" "nodes" ["~" "certname" "^host"]]
                       "nodes { certname ~ '^host' }"]]
          (is (= (set (mapv :certname (query-result method endpoint query {})))
                 #{"host1" "host2" "host3"}))))

      (testing "query should return single node info"
        (doseq [host ["host1" "host2" "host3"]
                query [["from" "nodes" ["=" "certname" host]]
                       (format "nodes { certname = '%s' }" host)]]
          (let [results (query-result method endpoint query)
                result (first results)]
            (is (= host (:certname result)))
            (if (= host "host3")
              (is (:deactivated result))
              (is (nil? (:deactivated result))))))))

    (testing "resources"
      (testing "query should return the resources just for that node"
        (doseq [host ["host1" "host2"]
                query [["from" "resources" ["=" "certname" host]]
                       (format "resources { certname = '%s' }" host)]]
          (let [results (query-result method endpoint query)]
            (is (= (set (map :certname results)) #{host})))))

      (testing "query should return the resources just for that node matching the supplied type"
        (doseq [host ["host1" "host2"]
                query [["from" "resources"
                        ["and"
                         ["=" "certname" host]
                         ["=" "type" "File"]]]
                       (format "resources { certname = '%s' and type = 'File' }" host)]]
          (let [results (query-result method endpoint query)]
            (is (= (set (map :certname results)) #{host}))
            (is (= (set (map :type results)) #{"File"}))
            (is (= (count results) 2)))))

      (testing "query should return all resources matching the supplied type"
        (doseq [query [["from" "resources" ["=" "type" "File"]]
                       "resources { type = 'File' }"]]
          (let [results (query-result method endpoint query)]
            (is (= (set (map :certname results)) #{"host1" "host2" "host3"}))
            (is (= (set (map :type results)) #{"File"}))
            (is (= (count results) 6)))))

      (testing "query should return [] if the <type> doesn't match anything"
        (doseq [query [["from" "resources" ["=" "type" "Foobar"]]
                       "resources { type = 'Foobar' }"]]
          (let [results (query-result method endpoint query)]
            (is (= results #{}))))))

    (testing "facts"
      (testing "query should return all instances of the given fact"
        (doseq [query [["from" "facts" ["=" "name" "kernel"]]
                       "facts { name = 'kernel' }"]]
          (let [results (query-result method endpoint query)]
            (is (= (set (map :name results)) #{"kernel"}))
            (is (= (count results) 3)))))

      (testing "query should return all instances of the given fact with the given value"
        (doseq [query [["from" "facts" ["and" ["=" "name" "kernel"] ["=" "value" "Linux"]]]
                       "facts { name = 'kernel' and value = 'Linux' }"]]
          (let [results (query-result method endpoint query)]
            (is (= (set (map :name results)) #{"kernel"}))
            (is (= (set (map :value results)) #{"Linux"}))
            (is (= (count results) 3)))))

      (testing "query should return [] if the fact doesn't match anything"
        (doseq [query [["from" "facts" ["=" "name" "blah"]]
                       "facts { name = 'blah' }"]]
          (let [results (query-result method endpoint query)]
            (is (= results #{}))))))

    (testing "fact_contents query should match expected results"
      (doseq [query [["from" "fact_contents" ["=" "certname" "host1"]]
                     "fact_contents { certname = 'host1' }"]]
        (let [results (query-result method endpoint query)]
          (is (= (set (mapv :name results))
                 #{"kernel" "operatingsystem" "fqdn"})))))

    (testing "group by"
      (doseq [query [["from" "facts"
                      ["extract"
                       ["name" ["function" "count"]]
                       ["group_by" "name"]]]
                     "facts [name, count()] { group by name }"]]
        (let [results (query-result method endpoint query)]
          (is (= results
                 #{{:name "fqdn" :count 3}
                   {:name "kernel" :count 3}
                   {:name "operatingsystem", :count 3}})))))

    (testing "in"
      (doseq [query [["from" "nodes"
                      ["in" "certname"
                       ["array"
                        ["host1" "host2"]]]]
                     "nodes { certname in ['host1', 'host2'] }"]]
        (let [results (query-result method endpoint query)]
          (is (= 2 (count results)))
          (is (= (set (map :certname results)) #{"host1" "host2"})))))))

(deftest ast-multi-comparisons
  (let [right-now (now)
        facts {:certname "foo.local"
               :environment "dev"
               :values {"foo" 1
                        "bar" 2
                        "baz" 3
                        "match" "match"}
               :timestamp right-now
               :producer_timestamp right-now
               :producer "bar.com"}]
    (with-test-db
      (scf-store/add-certname! "foo.local")
      (scf-store/add-facts! facts)
      (with-http-app
        (dotestseq [[version endpoint] endpoints
                    method [:get :post]]
          (let [query (partial query-result method endpoint)]
            (testing "that \"in\" fields can be duplicated"
              (is (= #{{:name "match" :value "match"}}
                     (query ["from" "facts"
                             ["extract" ["name" "value"]
                              ["in" ["value" "value"]
                               ["extract" ["name" "value"]
                                ["select_facts"]]]]]))))
            (testing "that \"in\" field order is respected"
              (is (= #{{:name "foo" :value 1}
                       {:name "bar" :value 2}
                       {:name "baz" :value 3}
                       {:name "match" :value "match"}}
                     (query ["from" "facts"
                             ["extract" ["name" "value"]
                              ["in" ["name" "value"]
                               ["extract" ["name" "value"]
                                ["select_facts"]]]]])))
              (is (= #{{:name "match" :value "match"}}
                     (query ["from" "facts"
                             ["extract" ["name" "value"]
                              ["in" ["name" "value"]
                               ["extract" ["value" "name"]
                                ["select_facts"]]]]]))))
            (testing "that rx match works across types"
              (is (= #{{:name "match" :value "match"}}
                     (query ["from" "facts"
                             ["extract" ["name" "value"]
                              ["~" "name" "match"]]]))))
            (testing "that \"in\" works if only the field is a multi"
              (is (= #{{:name "match" :value "match"}}
                     (query ["from" "facts"
                             ["extract" ["name" "value"]
                              ["in" ["value"]
                               ["extract" ["name"]
                                ["select_facts"]]]]]))))
            (testing "that \"in\" works if only the subquery projection is a multi"
              (is (= #{{:name "match" :value "match"}}
                     (query ["from" "facts"
                             ["extract" ["name" "value"]
                              ["in" ["name"]
                               ["extract" ["value"]
                                ["select_facts"]]]]]))))
            (testing "that \"in\" works if field and subquery projection are multi"
              (is (= #{{:name "foo" :value 1}
                       {:name "bar" :value 2}
                       {:name "baz" :value 3}
                       {:name "match" :value "match"}}
                     (query ["from" "facts"
                             ["extract" ["name" "value"]
                              ["in" ["value"]
                               ["extract" ["value"]
                                ["select_facts"]]]]])))))))))

  (deftest ast-fact-value-resource-title-join
    (let [certname "foo.local"
          environment "dev"
          right-now (now)
          facts {:certname certname
                 :environment environment
                 :values {"apache_conf" "/etc/apache/apache2.conf"
                          "foo" 1
                          "bar" 2
                          "baz" 3}
                 :timestamp right-now
                 :producer_timestamp right-now}
          resource {:type "File"
                    :title "/etc/apache/apache2.conf"
                    :exported false
                    :file "/tmp/foo"
                    :line 10
                    :tags #{"file" "class" "blarg"}
                    :parameters {:ensure "directory"
                                 :group "root"
                                 :source "my_file_source"
                                 :user "root"}}
          resources (-> (:empty examples/catalogs)
                        (assoc :certname certname)
                        (assoc :environment environment)
                        (update :resources
                                conj
                                [(select-keys resource [:type :title])
                                 resource]))]
      (with-test-db
        (scf-store/add-certname! certname)
        (scf-store/add-facts! facts)
        (scf-store/replace-catalog! resources (now))
        (with-http-app
          (dotestseq [[version endpoint] endpoints
                      method [:get :post]]
            (testing "that joins from resource title to fact value work"
              (is (= #{(-> resource
                           (update :tags set)
                           (dissoc :parameters) ;; don't care
                           (merge {:certname certname
                                   :environment environment}))}
                     (set (map #(-> %
                                    (dissoc :parameters :resource)
                                    (update :tags set))
                               (query-result
                                method endpoint
                                ["from" "resources"
                                 ["and"
                                  ["=" "type" "File"]
                                  ["in" ["title"]
                                   ["extract" ["value"]
                                    ["select_facts"
                                     ["=" "name" "apache_conf"]]]]]]))))))))))))
