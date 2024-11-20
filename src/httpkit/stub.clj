(ns httpkit.stub
  (:import [java.util.regex Pattern])
  (:require [org.httpkit.client :as http]
            [clojure.math.combinatorics :refer :all]
            [stub.shared :as shared]))

(defn- matches-url [url request]
  (let [parsed-url (if (string? url) (shared/parse-url url) url)
        req-map (shared/parse-url (:url request))
        req-str (shared/normalize-url-for-matching (shared/address-string-for req-map))]
    (cond
      (instance? Pattern url) 
      (re-matches url req-str)
      :else 
      (= (shared/normalize-url-for-matching (shared/address-string-for parsed-url))
         req-str))))

(defn- find-matching-route [routes request]
  (first
    (for [[url handlers] routes
          :when (matches-url url request)
          :let [method (:method request)
                handler (or (get handlers method)
                          (get handlers :any))
                times (or (get-in handlers [:times method])  ; Get method-specific times
                         (:times handlers))]                 ; Or global times
          :when (and handler (shared/methods-match? method request))]
      [url (fn [req] 
            ;; Set up expected counts if :times is specified
            (when times
              (swap! shared/*expected-counts* assoc (str url ":" (name method)) times))
            (handler (merge req 
                          {:url (:url request)
                           :method method
                           :query-params (:query-params request)})))])))

(defn wrap-request-with-stub [client]
  (fn [req callback]
    (let [request (shared/normalize-request req)
          matching-route (find-matching-route shared/*stub-routes* request)
          [url response] matching-route]
      (when url
        (swap! shared/*call-counts* update (str url ":" (name (:method request))) (fnil inc 0)))
      (let [response-promise (promise)]
        (if matching-route
          (deliver response-promise (shared/create-response response request))
          (if shared/*in-isolation*
            (throw (Exception. (str "No matching stub route found for " (:method request) " "
                                  (:url request))))
            (client req #(deliver response-promise %))))
        (callback @response-promise)
        response-promise))))

(defmacro with-http-stub
  "Makes all wrapped http-kit requests first match against given routes.
   Routes should be in the format:
   {\"http://example.com\" 
    {:get (fn [req] {:status 200})
     :post (fn [req] {:status 201})
     :any (fn [req] {:status 200})
     :times 2}}  ; New :times support"
  [routes & body]
  `(let [s# ~routes]
     (assert (map? s#))
     (binding [shared/*stub-routes* s#
               shared/*call-counts* (atom {})
               shared/*expected-counts* (atom {})]
       (with-redefs [http/request (wrap-request-with-stub http/request)]
         (try
           (let [result# (do ~@body)]
             (shared/validate-all-call-counts)
             result#)
           (finally
             (reset! shared/*call-counts* {})
             (reset! shared/*expected-counts* {})))))))

(defmacro with-http-stub-in-isolation
  "Makes all wrapped http-kit requests first match against given routes.
   If no route matches, an exception is thrown."
  [routes & body]
  `(binding [shared/*in-isolation* true]
     (with-http-stub ~routes ~@body)))

(defmacro with-global-http-stub
  "Makes all wrapped http-kit requests first match against given routes.
   The actual HTTP request will be sent only if no matches are found."
  [routes & body]
  `(let [s# ~routes]
     (assert (map? s#))
     (with-redefs [shared/*stub-routes* s#
                   shared/*call-counts* (atom {})
                   shared/*expected-counts* (atom {})
                   http/request (wrap-request-with-stub http/request)]
       (try
         (let [result# (do ~@body)]
           (shared/validate-all-call-counts)
           result#)
         (finally
           (reset! shared/*call-counts* {})
           (reset! shared/*expected-counts* {}))))))

(defmacro with-global-http-stub-in-isolation
  "Makes all wrapped http-kit requests first match against given routes.
   If no route matches, an exception is thrown."
  [routes & body]
  `(with-redefs [shared/*in-isolation* true]
     (with-global-http-stub ~routes ~@body)))
