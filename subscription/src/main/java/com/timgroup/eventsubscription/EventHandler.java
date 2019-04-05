package com.timgroup.eventsubscription;

import com.timgroup.eventstore.api.Position;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

public interface EventHandler {
    default void apply(Event deserialized) {
        throw new UnsupportedOperationException();
    }

    default void apply(Position position, Event deserialized) {
        apply(deserialized);
    }

    @Deprecated
    default void apply(Position position, Event deserialized, boolean endOfBatch) {
        apply(position, deserialized);
    }

    default EventHandler andThen(EventHandler o) {
        return SequencingEventHandler.flatten(Arrays.asList(this, requireNonNull(o)));
    }

    static EventHandler concat(EventHandler... handlers) {
        return SequencingEventHandler.flatten(Arrays.asList(handlers));
    }

    static EventHandler concatAll(List<? extends EventHandler> handlers) {
        return SequencingEventHandler.flatten(handlers);
    }

    static EventHandler ofConsumer(Consumer<? super Event> consumer) {
        requireNonNull(consumer);

        return new EventHandler() {
            @Override
            public void apply(Event deserialized) {
                consumer.accept(deserialized);
            }

            @Override
            public String toString() {
                return consumer.toString();
            }
        };
    }

    EventHandler DISCARD = ofConsumer(e -> {});
}
