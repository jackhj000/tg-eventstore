package com.timgroup.eventstore.memory;

import java.time.Clock;
import java.util.Collection;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Stream;

import com.timgroup.eventstore.api.*;
import com.timgroup.eventstore.api.legacy.LegacyStore;

public class JavaInMemoryEventStore implements EventStreamWriter, EventStreamReader, EventCategoryReader, EventReader {
    private final Collection<ResolvedEvent> events;
    private final Clock clock;

    public JavaInMemoryEventStore(Supplier<Collection<ResolvedEvent>> storageSupplier, Clock clock) {
        this.clock = clock;
        this.events = storageSupplier.get();
    }

    public JavaInMemoryEventStore(Clock clock) {
        this(CopyOnWriteArrayList::new, clock);
    }

    @Override
    public Stream<ResolvedEvent> readStreamForwards(StreamId streamId, long eventNumberExclusive) {
        internalReadStream(streamId, Long.MIN_VALUE)
                .findAny()
                .orElseThrow(() -> new NoSuchStreamException(streamId));
        return internalReadStream(streamId, eventNumberExclusive);
    }

    @Override
    public Stream<ResolvedEvent> readAllForwards() {
        return events.stream();
    }

    @Override
    public Stream<ResolvedEvent> readAllForwards(Position positionExclusive) {
        InMemoryEventStorePosition inMemoryPosition = (InMemoryEventStorePosition) positionExclusive;
        return readAllForwards().skip(inMemoryPosition.eventNumber);
    }

    @Override
    public void write(StreamId streamId, Collection<NewEvent> events) {
        write(streamId, events, currentVersionOf(streamId));
    }

    @Override
    public Position emptyStorePosition() {
        return new InMemoryEventStorePosition(0);
    }

    public EventStore toLegacy() {
        return new LegacyStore(this, this, StreamId.streamId("all", "all"), InMemoryEventStorePosition::new, p -> ((InMemoryEventStorePosition) p).eventNumber);
    }

    private long currentVersionOf(StreamId streamId) {
        return internalReadStream(streamId, EmptyStreamEventNumber)
                .mapToLong(r -> r.eventRecord().eventNumber())
                .max()
                .orElse(EmptyStreamEventNumber);
    }

    @Override
    public void write(StreamId streamId, Collection<NewEvent> events, long expectedVersion) {
        long currentVersion = currentVersionOf(streamId);

        if (currentVersion != expectedVersion) {
            throw new WrongExpectedVersionException(currentVersion, expectedVersion);
        }

        AtomicLong globalPosition = new AtomicLong(this.events.size());
        AtomicLong eventNumber = new AtomicLong(currentVersion);

        events.stream().map(newEvent -> new ResolvedEvent(new InMemoryEventStorePosition(globalPosition.incrementAndGet()), EventRecord.eventRecord(
                clock.instant(),
                streamId,
                eventNumber.incrementAndGet(),
                newEvent.type(),
                newEvent.data(),
                newEvent.metadata()
        ))).forEach(this.events::add);
    }

    @Override
    public Stream<ResolvedEvent> readCategoryForwards(String category) {
        return readAllForwards().filter(evt -> evt.eventRecord().streamId().category().equals(category));
    }

    @Override
    public Stream<ResolvedEvent> readCategoryForwards(String category, Position position) {
        return readAllForwards(position).filter(evt -> evt.eventRecord().streamId().category().equals(category));
    }

    private Stream<ResolvedEvent> internalReadStream(StreamId streamId, long eventNumberExclusive) {
        return events.stream()
                .filter(event -> event.eventRecord().streamId().equals(streamId))
                .filter(event -> event.eventRecord().eventNumber() > eventNumberExclusive);
    }

    private static final class InMemoryEventStorePosition implements Position {
        private final long eventNumber;

        private InMemoryEventStorePosition(long eventNumber) {
            this.eventNumber = eventNumber;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            InMemoryEventStorePosition that = (InMemoryEventStorePosition) o;

            return eventNumber == that.eventNumber;

        }

        @Override
        public int hashCode() {
            return (int) (eventNumber ^ (eventNumber >>> 32));
        }

        @Override
        public String toString() {
            return Long.toString(eventNumber);
        }
    }
}
