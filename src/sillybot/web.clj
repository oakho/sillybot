(ns sillybot.web
  (:use ring.util.response)
  (:require [compojure.core :refer [defroutes GET PUT POST DELETE ANY]]
            [compojure.handler :refer [site]]
            [compojure.route :as route]
            [clojure.java.io :as io]
            [ring.middleware.stacktrace :as trace]
            [ring.middleware.session :as session]
            [ring.middleware.session.cookie :as cookie]
            [ring.middleware.json :as json]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.basic-authentication :as basic]
            [cemerick.drawbridge :as drawbridge]
            [environ.core :refer [env]]))

(defn- authenticated? [user pass]
  ;; TODO: heroku config:add REPL_USER=[...] REPL_PASSWORD=[...]
  (= [user pass] [(env :repl-user false) (env :repl-password false)]))

(def ^:private drawbridge
  (-> (drawbridge/ring-handler)
      (session/wrap-session)
      (basic/wrap-basic-authentication authenticated?)))

(defn sillybot-static-response 
  "Build a static response"
  [sentence]
  (fn [] {:text sentence}))

(defn sillybot-random-response 
  "Build a random response"
  [& sentences]
  (fn [] {:text (rand-nth sentences)}))

(def sillybot-pool [{:condition "Toto" :response (sillybot-static-response "Bolo")}
                    {:condition "Bolo" :response (sillybot-static-response "Toto")}
                    {:condition "TotoBolo" :response (sillybot-static-response "They will rule the world, for sure !")}
                    {:condition #"Yolo" :response (sillybot-static-response "You silly, lol.")}
                    {:condition #"^[Should we|Should I]" :response (sillybot-random-response "Yes." "Nope." "I don't know." "Perhaps." "Why not ?")}])

(defn sillybot-matcher
  "Return approriate matching function according to given condition"
  [condition]
  (cond (= (type condition) java.util.regex.Pattern) re-find
        (= (type condition) java.lang.String) =))

(defn sillybot-match-pool-item?
  "Check if given item condition match incoming message"
  [item message]
  (let [condition (:condition item)]
    ((sillybot-matcher condition) condition message)))

(defn sillybot-parser
  "Parse incoming message and return an appropriate message if it matches a pool item condition"
  ([pool params] 
    (reduce 
      (fn [result item]
        (if (sillybot-match-pool-item? item (:text params))
          ((:response item)) 
          result))
      {}
      pool))
  ([params]
    (sillybot-parser sillybot-pool params)))

(defn sillybot-response
  "Return a response to incoming message"
  [params]
  (if (not (= (get params :user_name) "slackbot"))
    (response (sillybot-parser params))
    (response {})))

(defroutes app
  (ANY "/repl" {:as req}
       (drawbridge req))
  (GET "/" []
       (response "Hello there, lol."))
  (POST "/captain-hook" {params :params}
    (sillybot-response params))
  (ANY "*" []
       (route/not-found (slurp (io/resource "404.html")))))

(defn wrap-error-page [handler]
  (fn [req]
    (try (handler req)
         (catch Exception e
           {:status 500
            :headers {"Content-Type" "text/html"}
            :body (slurp (io/resource "500.html"))}))))

(defn -main [& [port]]
  (let [port (Integer. (or port (env :port) 5000))
        ;; TODO: heroku config:add SESSION_SECRET=$RANDOM_16_CHARS
        store (cookie/cookie-store {:key (env :session-secret)})]
    (jetty/run-jetty (-> #'app
                         ((if (env :production)
                            wrap-error-page
                            trace/wrap-stacktrace))
                         (json/wrap-json-body)
                         (json/wrap-json-params)
                         (json/wrap-json-response)
                         (site {:session {:store store}}))
                     {:port port :join? false})))

;; For interactive development:
;; (.stop server)
;; (def server (-main))
