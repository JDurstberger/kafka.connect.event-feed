(ns kafka.connect.event-feed.restart-test
  (:require
   [clojure.test :refer :all]

   [halboy.json :as haljson]

   [clj-wiremock.fixtures :as wmf]
   [clj-wiremock.core :as wmc]
   [clj-wiremock.utils :as wmu]

   [kafka.testing.combined :as ktc]

   [kafka.connect.client.core :as kcc]

   [kafka.connect.event-feed.test.logging]
   [kafka.connect.event-feed.test.resources :as tr]
   [kafka.connect.event-feed.test.consumer :as tc]
   [kafka.connect.event-feed.test.stubs.wiremock :as ts]
   [kafka.connect.event-feed.test.data :as td]
   [kafka.connect.event-feed.test.connector :as tcn]))

(comment
  (require '[kafka.connect.event-feed.task])
  (compile 'kafka.connect.event-feed.task))

(def kafka-atom (atom nil))
(def wiremock-atom (atom nil))

(use-fixtures :each
  (ktc/with-kafka kafka-atom)
  (wmf/with-wiremock wiremock-atom))

(deftest resumes-from-last-fetched-offset-when-connector-restarts
  (let [kafka (ktc/kafka @kafka-atom)
        kafka-connect (ktc/kafka-connect @kafka-atom)
        wiremock-server @wiremock-atom
        wiremock-url (wmu/base-url wiremock-server)

        topic-name :events

        event-resource-1-id (td/random-event-id)
        event-resource-1 (tr/event-resource wiremock-url
                           {:id   event-resource-1-id
                            :type :event-type-1})
        event-resource-2 (tr/event-resource wiremock-url
                           {:id   (td/random-event-id)
                            :type :event-type-2})
        event-resource-3-id (td/random-event-id)
        event-resource-3 (tr/event-resource wiremock-url
                           {:id   event-resource-3-id
                            :type :event-type-3})]
    (tcn/with-connector
      kafka-connect
      {:name   :event-feed-source
       :config {:connector.class           tcn/connector-class
                :topic.name                topic-name
                :eventfeed.discovery.url   (tr/discovery-href wiremock-url)
                :eventfeed.events.per.page 2}}
        ; first set of events, establish offset
      (wmc/with-stubs
        [(ts/discovery-resource wiremock-server)
         (ts/events-resource
           wiremock-server
           :events-link {:parameters {:per-page 2}}
           :events {:resources [event-resource-1]})
         (ts/events-resource
           wiremock-server
           :events-link {:parameters {:per-page 2 :since event-resource-1-id}}
           :events {:resources []})]
        (let [messages (tc/consume-n kafka topic-name 1)
              message-payloads (map #(get-in % [:value :payload]) messages)]
          (is (= [(haljson/resource->map event-resource-1)]
                message-payloads))))
        ; restart connector to ensure offset persisted
      (let [kafka-connect-client (tcn/kafka-connect-client kafka-connect)]
        (kcc/restart-connector
          kafka-connect-client :event-feed-source
          {:include-tasks? true}))
        ; second set of events, only allow resuming from page using previously
        ; persisted offset, will fail if offset not persisted
      (wmc/with-stubs
        [(ts/discovery-resource wiremock-server)
         (ts/events-resource
           wiremock-server
           :events-link {:parameters {:per-page 2 :since event-resource-1-id}}
           :events {:resources [event-resource-2 event-resource-3]})
         (ts/events-resource
           wiremock-server
           :events-link {:parameters {:per-page 2 :since event-resource-3-id}}
           :events {:resources []})]
        (let [messages (tc/consume-n kafka topic-name 3)
              message-payloads (map #(get-in % [:value :payload]) messages)]
          (is (= [(haljson/resource->map event-resource-1)
                  (haljson/resource->map event-resource-2)
                  (haljson/resource->map event-resource-3)]
                message-payloads)))))))
