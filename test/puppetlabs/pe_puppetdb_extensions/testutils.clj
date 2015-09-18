(ns puppetlabs.pe-puppetdb-extensions.testutils
  (:require [puppetlabs.trapperkeeper.core :refer [defservice]]
            [puppetlabs.trapperkeeper.services :refer [service-context service-id]]
            [puppetlabs.pe-puppetdb-extensions.sync.services :refer [puppetdb-sync-service]]
            [puppetlabs.pe-puppetdb-extensions.sync.pe-routing :refer [pe-routing-service]]
            [puppetlabs.puppetdb.pdb-routing :refer [pdb-routing-service]]
            [puppetlabs.kitchensink.core :as kitchensink]
            [compojure.core :refer [context POST routes ANY]]
            [puppetlabs.puppetdb.testutils.services :as svcs]
            [ring.middleware.params :refer [wrap-params]]
            [puppetlabs.puppetdb.utils :refer [base-url->str]]
            [puppetlabs.puppetdb.testutils :refer [clean-db-map postgres-map temp-dir]]
            [puppetlabs.puppetdb.cheshire :as json]
            [environ.core :refer [env]]
            [clj-http.client :as http])
   (:import [java.net MalformedURLException URISyntaxException URL]) )

(defservice stub-server-service
  [[:ConfigService get-in-config]
   [:WebroutingService add-ring-handler get-route]
   [:PuppetDBSync]]
  (start [this tk-context]
         (if-let [handler (get-in-config [:stub-server-service :handler])]
           (add-ring-handler this (wrap-params (context (get-route this) [] handler))))
         tk-context))

(def pe-services
  (concat [#'puppetdb-sync-service #'stub-server-service #'pe-routing-service]
          (remove #(= % #'pdb-routing-service) svcs/default-services)))

(defmacro with-puppetdb-instance
  "Same as the core call-with-puppetdb-instance call but adds in the
  sync service and the request-catcher/canned-response service"
  [config & body]
  `(svcs/call-with-puppetdb-instance
    ~config
    pe-services
    (fn [] ~@body)))

(def pdb-prefix "/pdb")
(def pdb-query-url-prefix (str pdb-prefix "/query"))
(def pdb-cmd-url-prefix (str pdb-prefix "/cmd"))
(def pe-pdb-url-prefix (str pdb-prefix "/ext"))
(def sync-url-prefix (str pdb-prefix "/sync"))
(def stub-url-prefix "/stub")

(def pdb2-postgres-map
  {:classname "org.postgresql.Driver"
   :subprotocol "postgresql"
   :subname (env :puppetdb2-dbsubname "//127.0.0.1:5432/puppetdb2_test")
   :user (env :puppetdb2-dbuser "puppetdb")
   :password (env :puppetdb2-dbpassword "puppetdb")})

(defn clean-pdb1-db-map [] (clean-db-map postgres-map))
(defn clean-pdb2-db-map [] (clean-db-map pdb2-postgres-map))

(defn create-config
  "Creates a default config, populated with a temporary vardir and
  a fresh hypersql instance"
  []
  {:nrepl {}
   :global {:vardir (temp-dir)}
   :jetty {:port 0}
   :command-processing {}})

(defn sync-config
  "Returns a default TK config setup for sync testing. PuppetDB is
  hosted at /pdb, and the sync service at /sync. Takes an optional
  `stub-handler` parameter, a ring handler that will be hosted under
  '/stub'."
  [stub-handler]
  (-> (create-config)
      (assoc-in [:sync :allow-unsafe-sync-triggers] true)
      (assoc :stub-server-service {:handler stub-handler}
             :web-router-service  {:puppetlabs.pe-puppetdb-extensions.sync.pe-routing/pe-routing-service pdb-prefix
                                   :puppetlabs.pe-puppetdb-extensions.testutils/stub-server-service stub-url-prefix})))

(defn pdb1-sync-config
  ([] (pdb1-sync-config nil))
  ([stub-handler]
   (-> (sync-config stub-handler)
       (assoc :database (clean-pdb1-db-map)))))

(defn pdb2-sync-config
  ([] (pdb2-sync-config nil))
  ([stub-handler]
   (-> (sync-config stub-handler)
       (assoc :database (clean-pdb2-db-map)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; URL helper functions for inside a with-puppetdb-instance block
(defn pdb-query-url []
  (assoc svcs/*base-url* :prefix pdb-query-url-prefix :version :v4))

(defn pdb-query-url-str []
  (base-url->str (pdb-query-url)))

(defn pdb-cmd-url []
  (assoc svcs/*base-url* :prefix pdb-cmd-url-prefix :version :v1))

(defn pdb-cmd-url-str []
  (base-url->str (pdb-cmd-url)))

(defn pe-pdb-url []
  (assoc svcs/*base-url* :prefix pe-pdb-url-prefix :version :v1))

(defn pe-pdb-url-str []
  (base-url->str (pe-pdb-url)))

(defn stub-url [prefix version]
  (svcs/*base-url* :prefix (str stub-url-prefix "/" prefix) :version version))

(defn stub-url-str [suffix]
  (let [{:keys [protocol host port] :as base-url} svcs/*base-url*]
   (-> (URL. protocol host port (str stub-url-prefix suffix))
       .toURI .toASCIIString)))

(defn sync-url []
  (assoc svcs/*base-url* :prefix sync-url-prefix :version :v1))

(defn trigger-sync-url-str []
  (str (base-url->str (sync-url)) "/trigger-sync"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; General utility functions
(defn index-by [key s]
  (into {} (for [val s] [(key val) val])))

(defn json-request [body]
  {:headers {"content-type" "application/json"}
   :throw-entire-message true
   :body (json/generate-string body)})

(defn json-response [m]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string m)})

(defn get-json [base-url suffix & [opts]]
  (let [opts (or opts {:throw-exceptions true
                       :throw-entire-message? true})]
    (-> (str (base-url->str base-url) suffix)
        (http/get opts)
        :body
        (json/parse-string true))))

(defn get-response
  [base-url suffix opts]
  (http/get (str (base-url->str base-url) suffix) opts))

;; alias to a different name because 'sync' means 'synchronous' here, and that's REALLY confusing.
(def blocking-command-post svcs/sync-command-post)
