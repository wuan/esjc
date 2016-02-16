package com.github.msemys.esjc;

import com.github.msemys.esjc.operation.UserCredentials;
import com.github.msemys.esjc.subscription.PersistentSubscriptionChannel;
import com.github.msemys.esjc.subscription.PersistentSubscriptionNakEventAction;
import com.github.msemys.esjc.util.Subscriptions.DropData;
import com.github.msemys.esjc.util.Throwables;
import com.github.msemys.esjc.util.concurrent.ResettableLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.msemys.esjc.util.Preconditions.checkArgument;
import static com.github.msemys.esjc.util.Subscriptions.DROP_SUBSCRIPTION_EVENT;
import static com.github.msemys.esjc.util.Subscriptions.UNKNOWN_DROP_DATA;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;

public abstract class PersistentSubscription {
    private static final Logger logger = LoggerFactory.getLogger(PersistentSubscription.class);

    private static final int MAX_EVENTS = 2000;

    private final String subscriptionId;
    private final String streamId;
    private final SubscriptionListener listener;
    private final UserCredentials userCredentials;
    private final boolean autoAck;

    private PersistentSubscriptionChannel subscription;
    private final Queue<ResolvedEvent> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean isProcessing = new AtomicBoolean();
    private final AtomicReference<DropData> dropData = new AtomicReference<>();
    private final AtomicBoolean isDropped = new AtomicBoolean();
    private final ResettableLatch stopped = new ResettableLatch(true);
    private final int bufferSize;
    private final Executor executor;

    protected PersistentSubscription(String subscriptionId,
                                     String streamId,
                                     SubscriptionListener listener,
                                     UserCredentials userCredentials,
                                     int bufferSize,
                                     boolean autoAck,
                                     Executor executor) {
        this.subscriptionId = subscriptionId;
        this.streamId = streamId;
        this.listener = listener;
        this.userCredentials = userCredentials;
        this.bufferSize = bufferSize;
        this.autoAck = autoAck;
        this.executor = executor;
    }

    protected void start() {
        stopped.reset();

        SubscriptionListener subscriptionListener = new SubscriptionListener() {
            @Override
            public void onEvent(ResolvedEvent event) {
                enqueue(event);
            }

            @Override
            public void onClose(SubscriptionDropReason reason, Exception exception) {
                enqueueSubscriptionDropNotification(reason, exception);
            }
        };

        subscription = await(startSubscription(subscriptionId, streamId, bufferSize, subscriptionListener, userCredentials));
    }

    protected abstract CompletableFuture<Subscription> startSubscription(String subscriptionId,
                                                                         String streamId,
                                                                         int bufferSize,
                                                                         SubscriptionListener listener,
                                                                         UserCredentials userCredentials);

    public void acknowledge(ResolvedEvent event) {
        subscription.notifyEventsProcessed(asList(event.originalEvent().eventId));
    }

    public void acknowledge(List<ResolvedEvent> events) {
        checkArgument(events.size() <= MAX_EVENTS, "events is limited to %d to ack at a time", MAX_EVENTS);
        subscription.notifyEventsProcessed(events.stream().map(e -> e.originalEvent().eventId).collect(toList()));
    }

    public void fail(ResolvedEvent event, PersistentSubscriptionNakEventAction action, String reason) {
        subscription.notifyEventsFailed(asList(event.originalEvent().eventId), action, reason);
    }

    public void fail(List<ResolvedEvent> events, PersistentSubscriptionNakEventAction action, String reason) {
        checkArgument(events.size() <= MAX_EVENTS, "events is limited to %d to ack at a time", MAX_EVENTS);
        subscription.notifyEventsFailed(events.stream().map(e -> e.originalEvent().eventId).collect(toList()), action, reason);
    }

    public void stop(Duration timeout) throws TimeoutException {
        logger.trace("Persistent Subscription to {}: requesting stop...", streamId);
        enqueueSubscriptionDropNotification(SubscriptionDropReason.UserInitiated, null);
        if (!stopped.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new TimeoutException(String.format("Could not stop %s in time.", getClass().getSimpleName()));
        }
    }

    private void enqueueSubscriptionDropNotification(SubscriptionDropReason reason, Exception exception) {
        // if drop data was already set -- no need to enqueue drop again, somebody did that already
        if (dropData.compareAndSet(null, new DropData(reason, exception))) {
            enqueue(DROP_SUBSCRIPTION_EVENT);
        }
    }

    private void enqueue(ResolvedEvent event) {
        queue.offer(event);
        if (isProcessing.compareAndSet(false, true)) {
            executor.execute(this::processQueue);
        }
    }

    private void processQueue() {
        do {
            ResolvedEvent event;
            while ((event = queue.poll()) != null) {
                // drop subscription artificial ResolvedEvent
                if (event.equals(DROP_SUBSCRIPTION_EVENT)) {
                    DropData previousDropData = dropData.getAndAccumulate(UNKNOWN_DROP_DATA,
                        (current, update) -> (current == null) ? update : current);

                    if (previousDropData == null) {
                        previousDropData = UNKNOWN_DROP_DATA;
                    }

                    dropSubscription(previousDropData.reason, previousDropData.exception);
                    return;
                }

                DropData currentDropData = dropData.get();
                if (currentDropData != null) {
                    dropSubscription(currentDropData.reason, currentDropData.exception);
                    return;
                }

                try {
                    listener.onEvent(event);

                    if (autoAck) {
                        subscription.notifyEventsProcessed(asList(event.originalEvent().eventId));
                    }

                    logger.trace("Persistent Subscription to {}: processed event ({}, {}, {} @ {}).", streamId,
                        event.originalEvent().eventStreamId, event.originalEvent().eventNumber,
                        event.originalEvent().eventType, event.originalEventNumber());
                } catch (Exception e) {
                    dropSubscription(SubscriptionDropReason.EventHandlerException, e);
                    return;
                }
            }
            isProcessing.compareAndSet(true, false);
        } while (!queue.isEmpty() && isProcessing.compareAndSet(false, true));
    }

    private void dropSubscription(SubscriptionDropReason reason, Exception exception) {
        if (isDropped.compareAndSet(false, true)) {
            logger.trace("Persistent Subscription to {}: dropping subscription, reason: {}", streamId, reason, exception);

            if (subscription != null) {
                subscription.unsubscribe();
            }

            listener.onClose(reason, exception);

            stopped.release();
        }
    }

    private static <T> T await(CompletableFuture<Subscription> subscriptionFuture) {
        try {
            return (T) subscriptionFuture.get();
        } catch (Exception e) {
            throw Throwables.propagate(e);
        }
    }

}
