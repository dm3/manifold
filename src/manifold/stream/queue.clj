(ns manifold.stream.queue
  (:require
    [manifold.stream.graph :as g]
    [manifold.deferred :as d]
    [manifold.stream :as s]
    [manifold.utils :as utils])
  (:import
    [java.lang.ref
     WeakReference]
    [java.util.concurrent.locks
     Lock]
    [java.util.concurrent.atomic
     AtomicReference
     AtomicInteger
     AtomicBoolean]
    [java.util.concurrent
     BlockingQueue
     LinkedBlockingQueue
     TimeUnit]
    [manifold.stream
     IEventSink
     IEventSource
     IStream]))

(s/def-source BlockingQueueSource
  [^BlockingQueue queue
   ^AtomicReference last-take]

  (isSynchronous [_]
    true)

  (description [_]
    {:type (.getCanonicalName (class queue))
     :buffer-size (.size queue)})

  (take [this blocking? default-val]
    (if blocking?

      (.take queue)

      (let [d  (d/deferred)
            d' (.getAndSet last-take d)
            f  (fn [_]
                 (let [x (.poll queue)]
                   (if (nil? x)

                     (utils/wait-for
                       (d/success! d (.take queue)))

                     (d/success! d x))))]
        (if (d/realized? d')
          (f nil)
          (d/on-realized d' f f))
        d)))

  (take [this blocking? default-val timeout timeout-val]
    (if blocking?

      (let [x (.poll queue timeout TimeUnit/MILLISECONDS)]
        (if (nil? x)
          timeout-val
          x))

      (let [d  (d/deferred)
            d' (.getAndSet last-take d)
            f  (fn [_]
                 (let [x (.poll queue)]
                   (if (nil? x)

                     (utils/wait-for
                       (d/success! d
                         (let [x (.poll queue timeout TimeUnit/MILLISECONDS)]
                           (if (nil? x)
                             timeout-val
                             x))))

                     (d/success! d x))))]
        (if (d/realized? d')
          (f nil)
          (d/on-realized d' f f))
        d))))


(s/def-sink BlockingQueueSink
  [^BlockingQueue queue
   ^AtomicReference last-put]

  (isSynchronous [_]
    true)

  (close [this]
    (.markClosed this))

  (description [_]
    (let [size (.size queue)]
      {:type (.getCanonicalName (class queue))
       :buffer-capacity (+ (.remainingCapacity queue) size)
       :buffer-size size}))

  (put [this x blocking?]

    (assert (not (nil? x)) "BlockingQueue cannot take `nil` as a message")

    (if blocking?

      (do
        (.put queue x)
        true)

      (let [d  (d/deferred)
            d' (.getAndSet last-put d)
            f  (fn [_]
                 (utils/with-lock lock
                   (try
                     (or
                       (and (s/closed? this)
                         (d/success! d false))

                       (and (.offer queue x)
                         (d/success! d true))

                       (utils/wait-for
                         (d/success! d
                           (do
                             (.put queue x)
                             true)))))))]
        (if (d/realized? d')
          (f nil)
          (d/on-realized d' f f))
        d)))

  (put [this x blocking? timeout timeout-val]

    (assert (not (nil? x)) "BlockingQueue cannot take `nil` as a message")

    (if blocking?

      (.offer queue x timeout TimeUnit/MILLISECONDS)

      (let [d  (d/deferred)
            d' (.getAndSet last-put d)
            f  (fn [_]
                 (utils/with-lock lock
                   (try
                     (or
                       (and (s/closed? this)
                         (d/success! d false))

                       (and (.offer queue x)
                         (d/success! d true))

                       (utils/wait-for
                         (d/success! d
                           (if (.offer queue x timeout TimeUnit/MILLISECONDS)
                             true
                             false)))))))]
        (if (d/realized? d')
          (f nil)
          (d/on-realized d' f f))
        d))))

;;;

(extend-protocol s/Sinkable

  BlockingQueue
  (to-sink [queue]
    (create-BlockingQueueSink
      queue
      (AtomicReference. (d/success-deferred true)))))

(extend-protocol s/Sourceable

  BlockingQueue
  (to-source [queue]
    (create-BlockingQueueSource
      queue
      (AtomicReference. (d/success-deferred true)))))
