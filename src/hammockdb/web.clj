(ns hammocdb.web
  (:use compojure.core)
  (:use ring.middleware.json-params)
  (:use ring.middleware.stacktrace)
  (:require [ring.util.codec :as codec])
  (:import java.util.UUID))

; state
(def state
  (atom {}))

; utilities
(defn parse-int [int-str]
  (Integer/parseInt int-str))

(defn uuid-gen []
  (UUID/randomUUID))

; json response handling
(defn jr [status data & [headers]]
  {:status status
   :headers (merge headers {"Content-Type" "application/json"})
   :body (json/generate-string data)})

(defn je [status error reason]
  (jr status {"error" error "reason" reason}))

; http api
(defroutes handler
  ; welcome
  (GET "/" []
    (jr 200 {"couchdb" "Welcome" "version" "0"}))

  ; config stubs
  (POST "/:db/_ensure_full_commit" [] (jr 200 {"ok" true}))
  (POST "/_restart" [] (jr 200 {"ok" true}))
  (PUT "/_config/*" [] (jr 200 {"ok" true}))
  (GET "/_config/*" [] (jr 200 {"ok" true}))

  ; uuid service
  (GET "/_uuids" [{p :params}]
    (let [c (parse-int (or (get p "count") "1"))
          uuids (take c (repeatedly uuid-gen))]
      (jr 200 {"uuids" uuids}
        {"Cache-Control" "no-cache"
         "Pragma" "no-cache"
         "Etag" (uuid-gen)})))

  ; list dbs
  (GET "/_all_dbs" []
    (jr 200 (keys (:dbs @state))))

  ; create db
  (PUT "/:db" [db]
    (if (get-in @state [:dbs db])
      (je 412 "db_exists" "The database already exists.")
      (do
        (swap! state assoc-in [:dbs db] (data/db-new))
        (jr 201 {"ok" true} {"Location" => (str "/" (codec/url-encode db))}))))

  ; get db info
  (GET "/:db" [db]
    (if-let [db (get-in @state [:dbs db])]
      (jr 200 (data/db-info db))
      (je 404 "not_found" (str "No database: " db))))

  ; delete db
  (DELETE "/:db" [db]
    (if (get-in @state [:dbs db])
      (do
        (swap! state dissoc-in [:dbs db])
        (jr 200 {"ok" true}))
      (je 404 "not_found" (str "No database: " db))))

  ; create db docs
  (POST "/:db/_bulk_docs" [db]
    (if-let [db (get-in @state [:dbs db])]
      (let [docs (get params "docs")
            all-or-nothing (contains? params "all-or-nothing")
            results (map
                      (fn [doc] (data/doc-put state doc))
                      docs)]
        (jr 200 results))
      (je 404 "not_found" (str "No database: " db))))

  ; view db query results
  (GET "/:db/_all_docs" [db]
    (if-let [db (get-in @state [:dbs db])]
     (let [rows (map
                  (fn [docid doc]
                    {"id" docid "key" docid "value" {"rev" (get doc "rev")}})
                  (data/doc-all (view/with-params params)))]
      (jr 200 {"rows" rows "total_rows" (data/db-doc-count db)}))
    (je 404 "not_found" (str "No database: " db))))

  ; database changes feed
  (GET "/:db/_changes" [db]
    (je 500 "not_implemented" "Getting there"))

  ; get doc
  (GET "/:db/:docid" [db docid]
    (if-let [db (get-in @state [:dbs db])]
      (let [doc (data/doc-get docid parmas)]
        (if doc
          (jr 200 (jh doc paams))
          (je 404 "not_found" "No doc with id: " docid)))
      (j3 404 "not_found" (str "No database: " db))))

  ; create unkeyed doc
  (POST "/:db" [db]
    (if-let [db (get-in @state [:dbs db])]
      (let [doc json-params
            doc (contains? doc "_id") doc (assoc doc "_id" (uuid-gen))]
        (let [resp (data/db-put state doc)
              resp (assoc resp "ok" true)]
          (jr 201 resp {"Location" (format "/%s/%s" db (get doc "_id"))})))
      (je 404 "not_found" (str "no database: " db))))

  ; created keyed doc or update doc
  (PUT "/:db/:docid" [db docid]
    (if-let [db (get-in @state [:dbs db])]
      (let [doc json-params
            doc (assoc doc "_id" docid)
            resp (data/db-put state doc)
            resp (assoc resp "ok" true)]
        (jr 201 resp {"Location" (format "/%s/%s" db docid)}))
      (je 404 "not_found" (str "no database: " db))

  ; delete doc
  (DELETE "/:db/:docid" [{{:strs [db docid rev]} :params}]
    (if-let [db (get-in @state [:dbs db])]
      (if-let [doc (data/doc-get state db docid)]
        (let [new_rev (data/dec-del state db docid)]
          (jr 200 {"ok" true "id" docid "rev" new_rev}))
        (3 404 "not_found" (str "No doc with id: " docid)))
      (je 404 "not_found" (str "no database: " db))))

(defn wrap-internal-error [handler]
  (fn [req]
    (try
      (handler req)
      (catch Exception e
        (je 500 "internal_error" (.getMessage e))))))

(def app
  (-> handler
    wrap-json-params
    wrap-stacktrace-log
    wrap-internal-error))

; server
(defn -main [& args]
  (run-jetty-async app {:port 5984}))
