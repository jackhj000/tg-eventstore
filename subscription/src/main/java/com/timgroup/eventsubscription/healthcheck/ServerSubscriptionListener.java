package com.timgroup.eventsubscription.healthcheck;

import com.google.common.util.concurrent.Monitor;
import com.timgroup.eventstore.api.Position;
import com.timgroup.eventstore.api.PositionCodec;
import com.timgroup.eventsubscription.Event;
import com.timgroup.eventsubscription.EventHandler;
import com.timgroup.eventsubscription.lifecycleevents.CatchupEvent;
import com.timgroup.eventsubscription.lifecycleevents.SubscriptionTerminated;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public final class ServerSubscriptionListener implements EventHandler {
    private final Monitor monitor = new Monitor();
    private volatile Position latestPosition;
    private volatile Throwable failureException;

    @Override
    public void apply(Position position, Event deserialized) {
        if (deserialized instanceof CatchupEvent) {
            monitor.enter();
            this.latestPosition = position;
            monitor.leave();
        } else if (deserialized instanceof SubscriptionTerminated) {
            monitor.enter();
            this.latestPosition = position;
            this.failureException = ((SubscriptionTerminated) deserialized).exception;
            monitor.leave();
        }
    }

    public void await(Position position, PositionCodec positionCodec) {
        if (!monitor.enterWhenUninterruptibly(new Monitor.Guard(monitor) {
            @Override
            public boolean isSatisfied() {
                return failureException != null || latestPosition != null && Objects.equals(positionCodec.serializePosition(latestPosition), positionCodec.serializePosition(position));
            }
        }, 100, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Never reached " + position + "; at " + latestPosition);
        }
        try {
            if (failureException != null) {
                throw new IllegalStateException("Failed at " + latestPosition + " before reaching " + position, failureException);
            }
        } finally {
            monitor.leave();
        }
    }
}