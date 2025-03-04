(ns clj-http.stub
  (:require [clj-http.core]
            [robert.hooke :refer [add-hook]]
            [clojure.math.combinatorics :refer [cartesian-product permutations]]
            [clojure.string :as str]
            [ring.util.codec :as ring-codec])
  (:import (java.nio.charset StandardCharsets)
           [org.apache.http HttpEntity]
           [java.util.regex Pattern]
           [java.util Map]))

(def ^:dynamic *stub-routes* {})
(def ^:dynamic *in-isolation* false)
(def ^:dynamic *call-counts* (atom {}))
(def ^:dynamic *expected-counts* (atom {}))

(defmacro with-http-stub
  [routes & body]
  `(with-stub-bindings ~routes (fn [] ~@body)))

(defmacro with-http-stub-in-isolation
  [routes & body]
  `(binding [*in-isolation* true]
     (with-http-stub ~routes ~@body)))

(defmacro with-global-http-stub
  [routes & body]
  `(with-global-http-stub-base ~routes (do ~@body)))

(defmacro with-global-http-stub-in-isolation
  [routes & body]
  `(with-redefs [*in-isolation* true]
     (with-global-http-stub ~routes ~@body)))

(defn utf8-bytes
  "Returns the UTF-8 bytes corresponding to the given string."
  ^bytes [^String s]
  (.getBytes s StandardCharsets/UTF_8))

(defn- byte-array?
  "Is `obj` a java byte array?"
  [obj]
  (instance? (Class/forName "[B") obj))

(defn body-bytes
  "If `obj` is a byte-array, return it, otherwise use `utf8-bytes`."
  [obj]
  (if (byte-array? obj)
    obj
    (utf8-bytes obj)))

(def normalize-path
  (memoize
    (fn [path]
      (cond
        (nil? path) "/"
        (str/blank? path) "/"
        (str/ends-with? path "/") path
        :else (str path "/")))))

(defn defaults-or-value
  "Given a set of default values and a value, returns either:
   - a vector of all default values (reversed) if the value is in the defaults
   - a vector containing just the value if it's not in the defaults"
  [defaults value]
  (if (contains? defaults value) (reverse (vec defaults)) (vector value)))

(def normalize-query-params
  "Normalizes query parameters to a consistent format.
   Handles both string and keyword keys, and converts all values to strings."
  (memoize
    (fn [params]
      (when params
        (into {} (for [[k v] params]
                   [(name k) (str v)]))))))

(def parse-query-string
  "Parses a query string into a map of normalized parameters.
   Returns empty map for nil or empty query string."
  (memoize
    (fn [query-string]
      (if (str/blank? query-string)
        {}
        (normalize-query-params (ring-codec/form-decode query-string))))))

(defn get-request-query-params
  "Extracts and normalizes query parameters from a request.
   Handles both :query-params and :query-string formats."
  [request]
  (or (some-> request :query-params normalize-query-params)
      (some-> request :query-string parse-query-string)
      {}))

(defn query-params-match?
  [expected-query-params request]
  (let [actual-query-params (get-request-query-params request)
        expected-query-params (normalize-query-params expected-query-params)]
    (= expected-query-params actual-query-params)))

(defn parse-url
  "Parse a URL string into a map containing :scheme, :server-name, :server-port, :uri, and :query-string"
  [url]
  (let [[url query] (str/split url #"\?" 2)
        [scheme rest] (if (str/includes? url "://")
                        (str/split url #"://" 2)
                        [nil url])
        [server-name path] (if (str/includes? rest "/")
                             (let [idx (str/index-of rest "/")]
                               [(subs rest 0 idx) (subs rest idx)])
                             [rest "/"])
        [server-name port] (if (str/includes? server-name ":")
                             (str/split server-name #":" 2)
                             [server-name nil])]
    {:scheme       scheme
     :server-name  server-name
     :server-port  (when port (Integer/parseInt port))
     :uri          (normalize-path path)
     :query-string query}))

(defn potential-server-ports-for
  [request-map]
  (defaults-or-value #{80 nil} (:server-port request-map)))

(defn potential-schemes-for
  [request-map]
  (let [scheme (:scheme request-map)
        scheme-val (if (keyword? scheme) :http "http")]
    (defaults-or-value #{scheme-val nil} scheme)))

(defn potential-query-strings-for
  [request-map]
  (let [queries (defaults-or-value #{"" nil} (:query-string request-map))
        query-supplied (= (count queries) 1)]
    (if query-supplied
      (map (partial str/join "&") (permutations (str/split (first queries) #"&|;")))
      queries)))

(defn potential-uris-for
  "Returns a set of potential URIs for a request.
   Uses defaults-or-value to handle common cases like '/', '', or nil.
   Also handles URIs with or without trailing slashes and normalized paths."
  [request-map]
  (let [uri (:uri request-map)]
    (if (and uri (not (or (= uri "/") (= uri "") (nil? uri))))
      (let [base-uris (defaults-or-value #{"/" "" nil} uri)
            with-slash (when (not (str/ends-with? uri "/"))
                         (str uri "/"))
            without-slash (when (and (str/ends-with? uri "/") (not= uri "/"))
                            (str/replace uri #"/+$" ""))]
        (cond-> (set base-uris)
          with-slash (conj with-slash)
          without-slash (conj without-slash)))
      (defaults-or-value #{"/" "" nil} uri))))

(defn potential-alternatives-to
  [request uris-fn]
  (let [schemes (potential-schemes-for request)
        server-ports (potential-server-ports-for request)
        uris (uris-fn request)
        query-params (:query-params request)
        query-string (when query-params
                       (ring-codec/form-encode query-params))
        query-strings (if query-string
                        [query-string]
                        (potential-query-strings-for request))
        combinations (cartesian-product query-strings schemes server-ports uris)]
    (map #(merge request (zipmap [:query-string :scheme :server-port :uri] %)) combinations)))

(def normalize-url-for-matching
  "Normalizes a URL string by removing trailing slashes for consistent matching"
  (memoize
    (fn [url]
      (str/replace url #"/+$" ""))))

(defn get-request-method
  "Gets the request method from the request map"
  [request]
  (:request-method request))

(defn methods-match?
  "Checks if a request method matches an expected method.
   Handles :any as a wildcard method."
  [expected-method request]
  (let [request-method (get-request-method request)]
    (contains? (set (distinct [:any request-method])) expected-method)))

(def address-string-for
  (memoize
    (fn [request-map]
      (let [{:keys [scheme server-name server-port uri query-string query-params]} request-map
            scheme-str (when scheme
                         (str (if (keyword? scheme) (name scheme) scheme) "://"))
            query-str (or query-string
                          (when query-params
                            (ring-codec/form-encode query-params)))]
        (str scheme-str
             server-name
             (when server-port (str ":" server-port))
             (when uri uri)
             (when query-str (str "?" query-str)))))))

(defprotocol RouteMatcher
  (matches [address method request]))

(extend-protocol RouteMatcher
  String
  (matches [address method request]
    (matches (re-pattern (Pattern/quote address)) method request))

  Pattern
  (matches [address method request]
    (let [address-strings (map address-string-for (potential-alternatives-to request potential-uris-for))
          request-url (or (:url request) (address-string-for request))]
      (and (methods-match? method request)
           (or (re-matches address request-url)
               (some #(re-matches address %) address-strings)))))

  Map
  (matches [address method request]
    (let [{expected-query-params :query-params} address]
      (and (or (nil? expected-query-params)
               (query-params-match? expected-query-params request))
           (let [request (cond-> request expected-query-params (dissoc :query-string))]
             (matches (:address address) method request))))))

(defn- process-handler [method address handler]
  (let [route-key (str address method)]
    (if (fn? handler)
      (if-let [times (:times (meta handler))]
        (do
          (swap! *expected-counts* assoc route-key times)
          [method address {:handler handler}])
        [method address {:handler handler}])
      (if (map? handler)
        (do
          (when-let [times (:times handler)]
            (swap! *expected-counts* assoc route-key times))
          [method address {:handler (:handler handler)}])
        [method address {:handler handler}]))))

(defn- flatten-routes [routes]
  (->> routes
       (mapcat (fn [[address handlers]]
                (if (map? handlers)
                  (keep (fn [[method handler]]
                         (when-not (= method :times)
                           (let [times (get-in handlers [:times method] (:times handlers))]
                             (process-handler method address 
                                            (cond-> handler
                                              times (with-meta {:times times}))))))
                       (dissoc handlers :times))
                  [[:any address {:handler handlers}]])))
       (map #(zipmap [:method :address :handler] %))))

(defn- get-matching-route
  [request]
  (->> *stub-routes*
       flatten-routes
       (filter #(matches (:address %) (:method %) request))
       first))


(defn create-response
  "Creates a response map with default values merged with the provided response.
   If response is a function, it will be called with the request as an argument.
   Returns a map with :status, :headers, and :body."
  [response request]
  (merge {:status  200
          :headers {}
          :body    ""}
         (if (fn? response)
           (response request)
           response)))

(defn normalize-request
  [request]
  (let [req (cond
              ;; Handle string URLs
              (string? request)
              {:url request}

              ;; Handle HttpEntity bodies
              (and (:body request) (instance? HttpEntity (:body request)))
              (assoc request :body (.getContent ^HttpEntity (:body request)))

              :else request)
        ;; Parse URL if it's a string
        req (if (string? (:url req))
              (merge req (parse-url (:url req)))
              req)
        ;; Ensure we have a method (default to :get)
        req (merge {:request-method :get} req)]
    (assoc req
      :request-method (:request-method req))))

(defn- handle-request-for-route
  [request route]
  (let [route-handler (:handler route)
        handler-fn (if (map? route-handler) (:handler route-handler) route-handler)
        route-key (str (:address route) (:method route))
        _ (swap! *call-counts* update route-key (fnil inc 0))
        response (create-response handler-fn (normalize-request request))]
    (update response :body body-bytes)))

(defn- throw-no-stub-route-exception
  [request]
  (throw (Exception.
           ^String
           (apply format
                  "No matching stub route found to handle request. Request details: \n\t%s \n\t%s \n\t%s \n\t%s \n\t%s "
                  (select-keys request [:scheme :request-method :server-name :uri :query-string])))))

(defn try-intercept
  ([origfn request respond raise]
   (if-let [matching-route (get-matching-route request)]
     (future
       (try (respond (handle-request-for-route request matching-route))
            (catch Exception e (raise e)))
       nil)
     (if *in-isolation*
       (try (throw-no-stub-route-exception request)
            (catch Exception e
              (raise e)
              (throw e)))
       (origfn request respond raise))))
  ([origfn request]
   (if-let [matching-route (get-matching-route request)]
     (handle-request-for-route request matching-route)
     (if *in-isolation*
       (throw-no-stub-route-exception request)
       (origfn request)))))

(defn validate-all-call-counts []
  (doseq [[route-key expected-count] @*expected-counts*]
    (let [actual-count (get @*call-counts* route-key 0)]
      (when (not= actual-count expected-count)
        (throw (Exception. (format "Expected route '%s' to be called %d times but was called %d times"
                                   route-key expected-count actual-count)))))))

(defn with-stub-bindings
  [routes body-fn]
  (assert (map? routes))
  (let [call-counts (atom {})
        expected-counts (atom {})]
    (try
      (binding [*stub-routes* routes
                *call-counts* call-counts
                *expected-counts* expected-counts]
        (let [result (body-fn)]
          (validate-all-call-counts)
          result))
      (finally
        (reset! call-counts {})
        (reset! expected-counts {})))))

(defmacro with-global-http-stub-base
  "Base implementation of with-global-http-stub that both clj-http and httpkit extend"
  [routes wrap-body & body]
  `(let [s# ~routes]
     (assert (map? s#))
     (with-redefs [*stub-routes* s#
                   *call-counts* (atom {})
                   *expected-counts* (atom {})]
       (with-stub-bindings s# (fn [] ~wrap-body)))))



(defn initialize-request-hook []
  (add-hook
   #'clj-http.core/request
   #'try-intercept))

(initialize-request-hook)
