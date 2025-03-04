(ns stub.shared
  (:import [org.apache.http HttpEntity]
           [java.util.regex Pattern]
           [java.util Map])
  (:require [clojure.math.combinatorics :refer [cartesian-product permutations]]
            [clojure.string :as str]
            [ring.util.codec :as ring-codec]))

(def ^:dynamic *stub-routes* {})
(def ^:dynamic *in-isolation* false)
(def ^:dynamic *call-counts* (atom {}))
(def ^:dynamic *expected-counts* (atom {}))

(defn normalize-path [path]
  (cond
    (nil? path) "/"
    (str/blank? path) "/"
    (str/ends-with? path "/") path
    :else (str path "/")))

(defn defaults-or-value
  "Given a set of default values and a value, returns either:
   - a vector of all default values (reversed) if the value is in the defaults
   - a vector containing just the value if it's not in the defaults"
  [defaults value]
  (if (contains? defaults value) (reverse (vec defaults)) (vector value)))

(defn normalize-query-params
  "Normalizes query parameters to a consistent format.
   Handles both string and keyword keys, and converts all values to strings."
  [params]
  (when params
    (into {} (for [[k v] params]
               [(name k) (str v)]))))

(defn parse-query-string
  "Parses a query string into a map of normalized parameters.
   Returns empty map for nil or empty query string."
  [query-string]
  (if (str/blank? query-string)
    {}
    (normalize-query-params (ring-codec/form-decode query-string))))

(defn get-request-query-params
  "Extracts and normalizes query parameters from a request.
   Handles both :query-params and :query-string formats."
  [request]
  (or (some-> request :query-params normalize-query-params)
      (some-> request :query-string parse-query-string)
      {}))

(defn query-params-match?
  "Checks if the actual query parameters in a request match the expected ones.
   Works with both query-string and query-params formats, and handles both
   httpkit and clj-http parameter styles."
  [expected-query-params request]
  (let [actual-query-params (get-request-query-params request)
        expected-query-params (normalize-query-params expected-query-params)]
    (and (= (count expected-query-params) (count actual-query-params))
         (every? (fn [[k v]]
                   (= v (get actual-query-params k)))
                 expected-query-params))))

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
  "Given a request map, returns a vector of potential server ports.
   If the request's server-port is 80 or nil, returns [80 nil],
   otherwise returns a vector with just the specified port."
  [request-map]
  (defaults-or-value #{80 nil} (:server-port request-map)))

(defn potential-schemes-for
  "Given a request map, returns a vector of potential schemes.
   Handles both string ('http') and keyword (:http) schemes.
   If the request's scheme is http/nil, returns [http nil],
   otherwise returns a vector with just the specified scheme."
  [request-map]
  (let [scheme (:scheme request-map)
        scheme-val (if (keyword? scheme) :http "http")]
    (defaults-or-value #{scheme-val nil} scheme)))

(defn potential-query-strings-for
  "Given a request map, returns a vector of potential query strings.
   If the request has no query string or an empty one, returns ['', nil].
   If it has a query string, returns all possible permutations of its parameters."
  [request-map]
  (let [queries (defaults-or-value #{"" nil} (:query-string request-map))
        query-supplied (= (count queries) 1)]
    (if query-supplied
      (map (partial str/join "&") (permutations (str/split (first queries) #"&|;")))
      queries)))

(defn potential-uris-for
  "Returns a set of potential URIs for a request.
   Uses defaults-or-value to handle common cases like '/', '', or nil."
  [request-map]
  (defaults-or-value #{"/" "" nil} (:uri request-map)))

(defn potential-alternatives-to
  "Given a request map and a function to generate potential URIs,
   returns a sequence of all possible alternative request maps
   by combining different schemes, server ports, URIs, and query strings.
   Each alternative preserves all other fields from the original request.
   
   The uris-fn parameter should be a function that takes a request map and returns
   a sequence of potential URIs for that request."
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

(defn normalize-url-for-matching
  "Normalizes a URL string by removing trailing slashes for consistent matching"
  [url]
  (str/replace url #"/+$" ""))

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

(defn address-string-for
  "Converts a request map into a URL string.
   Handles both keyword (:http) and string ('http') schemes.
   Returns a string in the format: scheme://server-name:port/uri?query-string
   where each component is optional."
  [request-map]
  (let [{:keys [scheme server-name server-port uri query-string query-params]} request-map
        scheme-str (when-not (nil? scheme)
                     (str (if (keyword? scheme) (name scheme) scheme) "://"))
        query-str (or query-string
                      (when query-params
                        (ring-codec/form-encode query-params)))]
    (str/join [scheme-str
               server-name
               (when-not (nil? server-port) (str ":" server-port))
               (when-not (nil? uri) uri)
               (when-not (nil? query-str) (str "?" query-str))])))

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

(defn validate-all-call-counts []
  (doseq [[route-key expected-count] @*expected-counts*]
    (let [actual-count (get @*call-counts* route-key 0)]
      (when (not= actual-count expected-count)
        (throw (Exception. (format "Expected route '%s' to be called %d times but was called %d times"
                                   route-key expected-count actual-count)))))))

(defn with-stub-bindings
  "Helper function to handle common stubbing logic across different stub macros.
   Takes a routes map and a body function, sets up the necessary bindings,
   executes the body, and ensures proper cleanup."
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
  "Normalizes a request map to a consistent format.
   - Converts string URLs to request maps
   - Sets default method to :get
   - Handles HttpEntity bodies
   - Ensures consistent method key (:request-method)
   - Handles clj-http request format"
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



