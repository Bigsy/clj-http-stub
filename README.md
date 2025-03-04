# clj-http-stub 
[![MIT License](https://img.shields.io/badge/license-MIT-brightgreen.svg?style=flat)](https://www.tldrlegal.com/l/mit) 
[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.bigsy/clj-http-stub.svg)](https://clojars.org/org.clojars.bigsy/clj-http-stub)

This is a library for stubbing out HTTP requests in Clojure. It supports clj-http client with a simple API. Includes both global and localised stubbing options for different testing scenarios. Stubbing can be isolated to specific test blocks to prevent unintended side effects.

## Usage

### Usage

```clojure
(ns myapp.test.core
   (:require [clj-http.client :as c])
   (:use clj-http.stub))
```

The public interface consists of macros:

* `with-fake-routes` - lets you override HTTP requests that match keys in the provided map
* `with-fake-routes-in-isolation` - does the same but throws if a request does not match any key
* `with-global-fake-routes`
* `with-global-fake-routes-in-isolation`

'Global' counterparts use `with-redefs` instead of `binding` internally so they can be used in
a multi-threaded environment.

### Examples

Example usage with clj-http:

```clojure
(with-http-stub
  {"https://api.weather.com/v1/current"
   (fn [request] {:status 200 :headers {} :body "Sunny, 72°F"})}
  (c/get "https://api.weather.com/v1/current"))

;; Route matching examples:
(with-http-stub
  {;; Exact string match:
   "https://api.github.com/users/octocat"
   (fn [request] {:status 200 :headers {} :body "{\"name\": \"The Octocat\"}"})

   ;; Exact string match with query params:
   "https://api.spotify.com/v1/search?q=beethoven&type=track"
   (fn [request] {:status 200 :headers {} :body "{\"tracks\": [...]}"})

   ;; Regexp match:
   #"https://([a-z]+).stripe.com/v1/customers"
   (fn [req] {:status 200 :headers {} :body "{\"customer\": \"cus_123\"}"})

   ;; Match based on HTTP method:
   "https://api.slack.com/api/chat.postMessage"
   {:post (fn [req] {:status 200 :headers {} :body "{\"ok\": true}"})}

   ;; Match multiple HTTP methods:
   "https://api.dropbox.com/2/files"
   {:get    (fn [req] {:status 200 :headers {} :body "{\"entries\": [...]}"})
    :delete (fn [req] {:status 401 :headers {} :body "{\"error\": \"Unauthorized\"}"})
    :any    (fn [req] {:status 200 :headers {} :body "{\"status\": \"success\"}"})}

   ;; Match using query params as a map
   {:address "https://api.openai.com/v1/chat/completions" :query-params {:model "gpt-4"}}
   (fn [req] {:status 200 :headers {} :body "{\"choices\": [...]}"})

   ;; If not given, the stub response status will be 200 and the body will be "".
   "https://api.twilio.com/2010-04-01/Messages"
   (constantly {})}

 ;; Your tests with requests here
 )
```
### Call Count Validation

You can specify and validate the number of times a route should be called using the `:times` option. There are two supported formats:

#### Simple Format
The `:times` option can be specified as a sibling of the HTTP methods:

```clojure
(with-http-stub
  {"https://api.example.com/data"
   {:get (fn [_] {:status 200 :body "ok"})
    :times 2}}
  
  ;; This will pass - route is called exactly twice as expected
  (c/get "https://api.example.com/data")
  (c/get "https://api.example.com/data"))

;; Multiple methods with shared count
(with-http-stub
  {"https://api.example.com/data"
   {:get (fn [_] {:status 200 :body "ok"})
    :post (fn [_] {:status 201 :body "created"})
    :times 1}}
  (c/get "https://api.example.com/data")
  (c/post "https://api.example.com/data"))
```

#### Per-Method Format
For more granular control, `:times` can be a map specifying counts per HTTP method:

```clojure
(with-http-stub
  {"https://api.example.com/data"
   {:get (fn [_] {:status 200 :body "ok"})
    :post (fn [_] {:status 201 :body "created"})
    :times {:get 2 :post 1}}}
  
  ;; This will pass - GET called twice, POST called once
  (c/get "https://api.example.com/data")
  (c/get "https://api.example.com/data")
  (c/post "https://api.example.com/data"))
```

The `:times` option allows you to:
- Verify a route is called exactly the expected number of times
- Ensure endpoints aren't called more times than necessary
- Specify different call counts for different HTTP methods

If the actual number of calls doesn't match the expected count, an exception is thrown with a descriptive message. 
If `:times` is not supplied, the route can be called any number of times.

### URL Matching Details

The library provides the following URL matching capabilities:

1. Default ports:
   ```clojure
   ;; These are equivalent:
   "http://example.com:80/api"
   "http://example.com/api"
   ```

2. Trailing slashes:
   ```clojure
   ;; These are equivalent:
   "http://example.com/api/"
   "http://example.com/api"
   ```

3. Default schemes:
   ```clojure
   ;; These are equivalent:
   "http://example.com"
   "example.com"
   ```

4. Query parameter order independence:
   ```clojure
   ;; These are equivalent:
   "http://example.com/api?a=1&b=2"
   "http://example.com/api?b=2&a=1"
   ```

### Async Support

The library supports async HTTP requests when using `with-global-http-stub`. This is particularly useful for testing code that makes HTTP requests from different threads:

```clojure
;; Example async test
(deftest async-http-test
  (testing "HTTP stubs work across different threads"
    (with-global-http-stub
      {"http://example.com/async"
       {:get (fn [_] {:status 200 :body "async response"})
        :times 2}}
      ;; Make requests from different threads
      (let [thread1 (future (c/get "http://example.com/async"))
            thread2 (future (c/get "http://example.com/async"))]
        @thread1
        @thread2))))
```

Note that you must use `with-global-http-stub` (not `with-http-stub`) for async support, as it uses `with-redefs` instead of `binding` to ensure the stubs are available across different threads.

The library works with any threading mechanism in Clojure, including `future`, `thread`, core.async, or custom thread pools. The key requirement is to use the global stub variants when testing code that makes HTTP requests from different threads.
