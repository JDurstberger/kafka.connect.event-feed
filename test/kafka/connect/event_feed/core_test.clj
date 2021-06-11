(ns kafka.connect.event-feed.core-test
  (:require
   [clojure.test :refer :all]

   [org.httpkit.fake :refer [with-fake-http]]

   [halboy.resource :as hal]
   [halboy.json :as hal-json])
  (:import
   [io.logicblocks.kafka.connect.eventfeed SourceTask]))

(deftest creates-valid-source-task
  (let [task (SourceTask.)]
    (is (instance? org.apache.kafka.connect.source.SourceTask task))))

; a page with no events will not have a next link
; a page with no events will have an empty array for embedded events?
; a page with no events will not have an embedded events key?
; any page with have a templatable "since" link expecting a event ID
; any page will have a self link including any query params
; a page where there are further pages of events will have a next link
;   encoding the correct query params
; termination logic for a given poll is that either next link missing or
;   embedded events is {missing|empty}
; alternatively, use max events per poll as page size and use ID of last event
;   on page as since offset

(deftest returns-empty-list-when-no-events-in-feed
  (let [task (SourceTask.)
        records (.poll task)

        no-events-resource
        (hal/new-resource "http://example.com/events")]
    (with-fake-http ["http://example.com/events"
                     (hal-json/resource->json no-events-resource)]
      (is (empty? records)))))

(compile 'kafka.connect.event-feed.task)