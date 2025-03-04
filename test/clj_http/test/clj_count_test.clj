(ns clj-http.test.clj-count-test
  (:require [clj-http.client :as http]
            [clj-http.stub :refer [*call-counts* *expected-counts* validate-all-call-counts]])
  (:use [clj-http.stub]
        [clojure.test]
        :reload-all))

(deftest times-mismatch-includes-route-info
  (try
    (with-http-stub
      {"http://example.com/test" {:get (with-meta
                                         (fn [_] {:status 200 :headers {} :body "response"})
                                         {:times 2})}}
      (http/get "http://example.com/test"))
    (is false "Should have thrown an exception")
    (catch Exception e
      (is (re-find #"Expected route 'http://example.com/test:get' to be called 2 times but was called 1 times"
                   (.getMessage e))))))

(deftest expected-call-count-test
  (testing "passes when route is called expected number of times"
    (with-http-stub
      {"http://example.com"
       {:get (fn [_] {:status 200 :body "ok"})
        :times 2}}
      (http/get "http://example.com")
      (http/get "http://example.com")))

  (testing "fails when route is called less than expected times"
    (try
      (with-http-stub
        {"http://example.com"
         {:get (fn [_] {:status 200 :body "ok"})
          :times 2}}
        (http/get "http://example.com"))
      (is false "Should have thrown an exception")
      (catch Exception e
        (is (= (.getMessage e) "Expected route 'http://example.com:get' to be called 2 times but was called 1 times")))))

  (testing "fails when route is called more than expected times"
    (try
      (with-http-stub
        {"http://example.com"
         {:get (fn [_] {:status 200 :body "ok"})
          :times 1}}
        (http/get "http://example.com")
        (http/get "http://example.com"))
      (is false "Should have thrown an exception")
      (catch Exception e
        (is (= (.getMessage e) "Expected route 'http://example.com:get' to be called 1 times but was called 2 times"))))))

(deftest multiple-routes-with-times-test
  (testing "passes when multiple routes are called their expected number of times"
    (with-http-stub
      {"http://example.com/api1"
       {:get (fn [_] {:status 200 :body "ok1"})
        :times 2}
       "http://example.com/api2"
       {:get (fn [_] {:status 200 :body "ok2"})
        :times 1}}
      (http/get "http://example.com/api1")
      (http/get "http://example.com/api2")
      (http/get "http://example.com/api1")))

  (testing "fails when any route is not called its expected number of times"
    (try
      (with-http-stub
        {"http://example.com/api1"
         {:get (fn [_] {:status 200 :body "ok1"})
          :times 2}
         "http://example.com/api2"
         {:get (fn [_] {:status 200 :body "ok2"})
          :times 2}}
        (http/get "http://example.com/api1")
        (http/get "http://example.com/api2"))
      (is false "Should have thrown an exception")
      (catch Exception e
        (is (= (.getMessage e) "Expected route 'http://example.com/api1:get' to be called 2 times but was called 1 times"))))))

(deftest times-with-different-methods-test
  (testing "passes when route is called expected number of times with different methods"
    (with-http-stub
      {"http://example.com"
       {:get (fn [_] {:status 200 :body "ok"})
        :post (fn [_] {:status 201 :body "created"})
        :times {:get 1 :post 2}}}
      (http/get "http://example.com")
      (http/post "http://example.com")
      (http/post "http://example.com")))

  (testing "fails when any method is not called its expected number of times"
    (try
      (with-http-stub
        {"http://example.com"
         {:get (fn [_] {:status 200 :body "ok"})
          :post (fn [_] {:status 201 :body "created"})
          :times {:get 1 :post 2}}}
        (http/get "http://example.com")
        (http/post "http://example.com"))
      (is false "Should have thrown an exception")
      (catch Exception e
        (is (= (.getMessage e) "Expected route 'http://example.com:post' to be called 2 times but was called 1 times"))))))

(deftest times-edge-cases-test
  (testing "passes when route with :times 0 is never called"
    (with-http-stub
      {"http://example.com"
       {:get (fn [_] {:status 200 :body "ok"})
        :times 0}}
      (is true)))

  (testing "fails when route with :times 0 is called"
    (try
      (with-http-stub
        {"http://example.com"
         {:get (fn [_] {:status 200 :body "ok"})
          :times 0}}
        (http/get "http://example.com"))
      (is false "Should have thrown an exception")
      (catch Exception e
        (is (= (.getMessage e) "Expected route 'http://example.com:get' to be called 0 times but was called 1 times"))))))

(deftest shared-count-with-different-methods-test
  (testing "passes when multiple methods share a count and are called expected number of times"
    (with-http-stub
      {"http://example.com"
       {:get (fn [_] {:status 200 :body "ok"})
        :post (fn [_] {:status 201 :body "created"})
        :times 1}}
      (http/get "http://example.com")
      (http/post "http://example.com")))

  (testing "fails when shared count is exceeded by any method"
    (try
      (with-http-stub
        {"http://example.com"
         {:get (fn [_] {:status 200 :body "ok"})
          :post (fn [_] {:status 201 :body "created"})
          :times 1}}
        (http/get "http://example.com")
        (http/get "http://example.com"))
      (is false "Should have thrown an exception")
      (catch Exception e
        (is (= (.getMessage e) "Expected route 'http://example.com:get' to be called 1 times but was called 2 times"))))))

(defmacro other-thread
  "Mostly like future but fails to preserve thread-local bindings."
  [& body]
  `(let [p# (promise)
         t# (new Thread (fn [] (deliver p# (do ~@body))))]     
     (.start t#)
     p#))

(deftest async-call-count-test
  (testing "passes when route is called expected number of times asynchronously"
    (is (= (with-global-http-stub
             {"http://example.com/async"
              {:get (fn [_] {:status 200 :body "async ok"})
               :times 2}}
             (let [resp1 @(other-thread (http/get "http://example.com/async"))
                   resp2 @(other-thread (http/get "http://example.com/async"))]
               (str (:body resp1) "-" (:body resp2))))
           "async ok-async ok"))))

(deftest async-call-count-with-different-methods-test
  (testing "passes when multiple methods are called their expected number of times asynchronously"
    (is (= (with-global-http-stub
             {"http://example.com/async-methods"
              {:get (fn [_] {:status 200 :body "async get"})
               :post (fn [_] {:status 201 :body "async post"})
               :times {:get 1 :post 1}}}
             (let [resp1 @(other-thread (http/get "http://example.com/async-methods"))
                   resp2 @(other-thread (http/post "http://example.com/async-methods"))]
               (str (:body resp1) "-" (:body resp2))))
           "async get-async post"))))

(comment
  (with-http-stub
    {"http://example.com"
     {:get (fn [_] {:status 200 :body "ok"})
      :times 1}}
    (http/get "http://example.com")))
