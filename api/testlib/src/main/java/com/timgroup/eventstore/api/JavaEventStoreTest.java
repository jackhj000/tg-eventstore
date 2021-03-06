package com.timgroup.eventstore.api;

import com.timgroup.eventstore.api.EventStreamWriter.StreamWriteRequest;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.hamcrest.TypeSafeDiagnosingMatcher;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static com.timgroup.eventstore.api.EventStreamReader.EmptyStreamEventNumber;
import static com.timgroup.eventstore.api.NewEvent.newEvent;
import static com.timgroup.eventstore.api.ObjectPropertiesMatcher.objectWith;
import static com.timgroup.eventstore.api.StreamId.streamId;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static java.util.stream.LongStream.range;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

@SuppressWarnings({"ResultOfMethodCallIgnored", "OptionalGetWithoutIsPresent"})
public abstract class JavaEventStoreTest {
    @Rule
    public ExpectedException thrown = ExpectedException.none();

    private final String category_1 = randomCategory();
    private final String category_2 = randomCategory();
    private final String category_3 = randomCategory();
    
    private final StreamId stream_1 = streamId(category_1, "1");
    private final StreamId stream_2 = streamId(category_2, "2");
    private final StreamId stream_3 = streamId(category_3, "3");

    private final NewEvent event_1 = newEvent("type-A", randomData(), randomData());
    private final NewEvent event_2 = newEvent("type-B", randomData(), randomData());
    private final NewEvent event_3 = newEvent("type-C", randomData(), randomData());
    private final NewEvent event_4 = newEvent("type-D", randomData(), randomData());
    private final NewEvent event_5 = newEvent("type-E", randomData(), randomData());

    public abstract EventSource eventSource();

    private Instant timeBeforeTest;

    @Before
    public void captureTime() {
        timeBeforeTest = Instant.now();
    }

    @Test
    public void
    can_read_written_events() {
        eventSource().writeStream().write(stream_1, asList(
                event_1, event_2
        ));

        assertThat(eventSource().readStream().readStreamForwards(stream_1).map(ResolvedEvent::eventRecord).collect(toList()), contains(
                objectWith(EventRecord::streamId, stream_1)
                        .and(EventRecord::eventNumber, 0L)
                        .and(EventRecord::eventType, event_1.type())
                        .and(EventRecord::data, event_1.data())
                        .and(EventRecord::metadata, event_1.metadata())
                        .andMatching(EventRecord::timestamp, shortlyAfter(timeBeforeTest)),
                objectWith(EventRecord::streamId, stream_1)
                        .and(EventRecord::eventNumber, 1L)
                        .and(EventRecord::eventType, event_2.type())
                        .and(EventRecord::data, event_2.data())
                        .and(EventRecord::metadata, event_2.metadata())
                        .andMatching(EventRecord::timestamp, shortlyAfter(timeBeforeTest))
        ));
    }

    @Test
    public void
    returns_sub_second_precision_for_read_events() {
        for (int i = 0; i < 50; i++) {
            eventSource().writeStream().write(stream_1, singletonList(event_1));
        }

        Set<Integer> eventTimestampFractionsOfSeconds = eventSource().readStream().readStreamForwards(stream_1)
                .map(ResolvedEvent::eventRecord)
                .map(e -> e.timestamp().getNano())
                .collect(toSet());

        // - in theory, this might accidentally fail if all events have 0 nanos in their timestamps
        // - in practice, this is highly unlikely though, since even for the in-memory store subsequently written events have slightly different nanos in their timestamps
        // - is there must be a better way to test / ensure that we return timestamps with sub-second precision?
        assertThat(eventTimestampFractionsOfSeconds, not(Matchers.contains(0)));
    }

    @Test
    public void
    cannot_read_from_stream_after_reaching_end_despite_writing_more_events() {
        eventSource().writeStream().write(stream_1, singletonList(
                event_1
        ));

        Iterator<ResolvedEvent> it = eventSource().readStream().readStreamForwards(stream_1).iterator();
        assertThat(it.hasNext(), is(true));
        it.next();
        assertThat(it.hasNext(), is(false));

        eventSource().writeStream().write(stream_1, singletonList(
                event_2
        ));
        assertThat(it.hasNext(), is(false));
    }

    @Test
    public void
    can_read_and_write_to_streams_independently() {
        eventSource().writeStream().write(stream_1, singletonList(event_1));
        eventSource().writeStream().write(stream_2, singletonList(event_2));

        assertThat(eventSource().readStream().readStreamForwards(stream_1).map(ResolvedEvent::eventRecord).collect(toList()), contains(
                objectWith(EventRecord::eventNumber, 0L).and(EventRecord::streamId, stream_1)
        ));
        assertThat(eventSource().readStream().readStreamForwards(stream_2).map(ResolvedEvent::eventRecord).collect(toList()), contains(
                objectWith(EventRecord::eventNumber, 0L).and(EventRecord::streamId, stream_2)
        ));
    }

    @Test
    public void
    can_read_from_specific_event_number() {
        eventSource().writeStream().write(stream_1, asList(
                event_1, event_2
        ));

        assertThat(eventSource().readStream().readStreamForwards(stream_1, 0).map(ResolvedEvent::eventRecord).collect(toList()), contains(
                objectWith(EventRecord::eventNumber, 1L)
        ));
    }

    @Test
    public void
    can_read_empty_set_of_events_from_end_of_stream() {
        eventSource().writeStream().write(stream_1, asList(
                event_1, event_2
        ));

        assertThat(eventSource().readStream().readStreamForwards(stream_1, 1).collect(toList()), hasSize(0));
    }

    @Test
    public void
    can_read_event_stream_backwards() {
        eventSource().writeStream().write(stream_1, singletonList(anEvent()));
        eventSource().writeStream().write(stream_3, singletonList(anEvent()));
        eventSource().writeStream().write(stream_2, singletonList(anEvent()));
        eventSource().writeStream().write(stream_1, singletonList(anEvent()));

        assertThat(eventSource().readStream().readStreamBackwards(stream_1).map(ResolvedEvent::eventRecord).collect(toList()), contains(
                objectWith(EventRecord::streamId, stream_1).and(EventRecord::eventNumber, 1L),
                objectWith(EventRecord::streamId, stream_1).and(EventRecord::eventNumber, 0L)
        ));
    }

    @Test
    public void
    can_continue_reading_stream_backwards_from_position() {
        eventSource().writeStream().write(stream_1, singletonList(anEvent()));
        eventSource().writeStream().write(stream_3, singletonList(anEvent()));
        eventSource().writeStream().write(stream_2, singletonList(anEvent()));
        eventSource().writeStream().write(stream_1, singletonList(anEvent()));

        assertThat(eventSource().readStream().readStreamBackwards(stream_1, 1L).map(ResolvedEvent::eventRecord).collect(toList()), contains(
                objectWith(EventRecord::streamId, stream_1).and(EventRecord::eventNumber, 0L)
        ));
    }

    @Test
    public void
    can_continue_reading_stream_backwards_from_position_at_beginning_of_stream() {
        eventSource().writeStream().write(stream_1, singletonList(event_1));
        eventSource().writeStream().write(stream_2, singletonList(event_2));
        eventSource().writeStream().write(stream_3, singletonList(event_3));

        assertThat(eventSource().readStream().readStreamBackwards(stream_1, 0L).collect(toList()), empty());
    }

    @Test
    public void
    can_read_last_event_in_stream() {
        eventSource().writeStream().write(stream_1, singletonList(anEvent()));
        eventSource().writeStream().write(stream_3, singletonList(anEvent()));
        eventSource().writeStream().write(stream_2, singletonList(anEvent()));
        eventSource().writeStream().write(stream_1, singletonList(anEvent()));

        assertThat(eventSource().readStream().readLastEventInStream(stream_1).eventRecord(), objectWith(EventRecord::streamId, stream_1).and(EventRecord::eventNumber, 1L));
    }

    @Test
    public void
    throws_exception_when_stream_does_not_exist_on_stream_creation() {
        EventStreamReader eventStreamReader = eventSource().readStream();

        thrown.expect(NoSuchStreamException.class);
        eventStreamReader.readStreamForwards(stream_1);
    }

    @Test
    public void
    throws_exception_when_stream_does_not_exist_on_stream_creation_with_event_number() {
        EventStreamReader eventStreamReader = eventSource().readStream();

        thrown.expect(NoSuchStreamException.class);
        eventStreamReader.readStreamForwards(stream_1, 0);
    }

    @Test
    public void
    throws_exception_when_stream_does_not_exist_on_backwards_stream_creation() {
        EventStreamReader eventStreamReader = eventSource().readStream();

        thrown.expect(NoSuchStreamException.class);
        eventStreamReader.readStreamBackwards(stream_1);
    }

    @Test
    public void
    throws_exception_when_stream_does_not_exist_on_backwards_stream_creation_with_event_number() {
        EventStreamReader eventStreamReader = eventSource().readStream();

        thrown.expect(NoSuchStreamException.class);
        eventStreamReader.readStreamBackwards(stream_1, Long.MAX_VALUE);
    }

    @Test
    public void
    throws_exception_when_stream_does_not_exist_on_reading_last_event() {
        EventStreamReader eventStreamReader = eventSource().readStream();

        thrown.expect(NoSuchStreamException.class);
        eventStreamReader.readLastEventInStream(stream_1);
    }

    @Test
    public void
    can_read_all_events() {
        eventSource().writeStream().write(stream_1, singletonList(event_1));
        eventSource().writeStream().write(stream_2, singletonList(event_2));
        eventSource().writeStream().write(stream_3, singletonList(event_3));

        assertThat(eventSource().readAll().readAllForwards().map(ResolvedEvent::eventRecord).collect(toList()), contains(
                objectWith(EventRecord::streamId, stream_1).and(EventRecord::eventNumber, 0L),
                objectWith(EventRecord::streamId, stream_2).and(EventRecord::eventNumber, 0L),
                objectWith(EventRecord::streamId, stream_3).and(EventRecord::eventNumber, 0L)
        ));
    }

    @Test
    public void
    can_continue_reading_all_from_position() {
        eventSource().writeStream().write(stream_1, singletonList(event_1));
        eventSource().writeStream().write(stream_2, singletonList(event_2));
        eventSource().writeStream().write(stream_3, singletonList(event_3));

        try (Stream<ResolvedEvent> stream = eventSource().readAll().readAllForwards()) {
            Position position = stream.limit(1).reduce((a, b) -> b).get().position();

            assertThat(eventSource().readAll().readAllForwards(position).map(ResolvedEvent::eventRecord).collect(toList()), contains(
                    objectWith(EventRecord::streamId, stream_2).and(EventRecord::eventNumber, 0L),
                    objectWith(EventRecord::streamId, stream_3).and(EventRecord::eventNumber, 0L)
            ));
        }
    }

    @Test
    public void
    can_continue_reading_all_from_position_at_end_of_stream() {
        eventSource().writeStream().write(stream_1, singletonList(event_1));
        eventSource().writeStream().write(stream_2, singletonList(event_2));
        eventSource().writeStream().write(stream_3, singletonList(event_3));

        try (Stream<ResolvedEvent> stream = eventSource().readAll().readAllForwards()) {
            Position position = stream.reduce((a, b) -> b).get().position();

            assertThat(eventSource().readAll().readAllForwards(position).collect(toList()), empty());
        }
    }

    @Test
    public void
    can_read_all_events_backwards() {
        eventSource().writeStream().write(stream_1, singletonList(event_1));
        eventSource().writeStream().write(stream_2, singletonList(event_2));
        eventSource().writeStream().write(stream_3, singletonList(event_3));

        assertThat(eventSource().readAll().readAllBackwards().map(ResolvedEvent::eventRecord).collect(toList()), contains(
                objectWith(EventRecord::streamId, stream_3).and(EventRecord::eventNumber, 0L),
                objectWith(EventRecord::streamId, stream_2).and(EventRecord::eventNumber, 0L),
                objectWith(EventRecord::streamId, stream_1).and(EventRecord::eventNumber, 0L)
        ));
    }

    @Test
    public void
    can_continue_reading_all_backwards_from_position() {
        eventSource().writeStream().write(stream_1, singletonList(event_1));
        eventSource().writeStream().write(stream_2, singletonList(event_2));
        eventSource().writeStream().write(stream_3, singletonList(event_3));

        try (Stream<ResolvedEvent> stream = eventSource().readAll().readAllBackwards()) {
            Position position = stream.limit(1).reduce((a, b) -> b).get().position();

            assertThat(eventSource().readAll().readAllBackwards(position).map(ResolvedEvent::eventRecord).collect(toList()), contains(
                    objectWith(EventRecord::streamId, stream_2).and(EventRecord::eventNumber, 0L),
                    objectWith(EventRecord::streamId, stream_1).and(EventRecord::eventNumber, 0L)
            ));
        }
    }

    @Test
    public void
    can_continue_reading_all_backwards_from_position_at_beginning_of_stream() {
        eventSource().writeStream().write(stream_1, singletonList(event_1));
        eventSource().writeStream().write(stream_2, singletonList(event_2));
        eventSource().writeStream().write(stream_3, singletonList(event_3));

        try (Stream<ResolvedEvent> stream = eventSource().readAll().readAllBackwards()) {
            Position position = stream.reduce((a, b) -> b).get().position();

            assertThat(eventSource().readAll().readAllBackwards(position).collect(toList()), empty());
        }
    }

    @Test
    public void
    fails_if_expected_version_has_not_been_reached() {
        thrown.expect(WrongExpectedVersionException.class);
        eventSource().writeStream().write(stream_1, singletonList(event_2), 0);
    }

    @Test
    public void
    fails_if_expected_version_has_passed() {
        eventSource().writeStream().write(stream_1, singletonList(event_1));
        eventSource().writeStream().write(stream_1, singletonList(event_2));

        thrown.expect(WrongExpectedVersionException.class);
        eventSource().writeStream().write(stream_1, singletonList(event_3), 0);
    }

    @Test
    public void
    writes_when_expected_version_matches() {
        eventSource().writeStream().write(stream_1, singletonList(event_1));

        eventSource().writeStream().write(stream_1, singletonList(event_2), 0);

        assertThat(eventSource().readStream().readStreamForwards(stream_1).map(ResolvedEvent::eventRecord).collect(toList()), contains(
                objectWith(EventRecord::eventNumber, 0L),
                objectWith(EventRecord::eventNumber, 1L)
        ));
    }

    @Test public void
    can_write_expecting_empty_stream() {
        eventSource().writeStream().write(stream_1, singletonList(event_1), EmptyStreamEventNumber);

        assertThat(eventSource().readStream().readStreamForwards(stream_1).count(), is(1L));
    }

    @Test
    public void
    can_read_events_by_category() {
        NewEvent event1 = anEvent();
        NewEvent event4 = anEvent();
        eventSource().writeStream().write(streamId(category_1, "Id1"), singletonList(event1));
        eventSource().writeStream().write(streamId(category_3, "Id1"), singletonList(anEvent()));
        eventSource().writeStream().write(streamId(category_2, "Id1"), singletonList(anEvent()));
        eventSource().writeStream().write(streamId(category_1, "Id2"), singletonList(event4));

        assertThat(eventSource().readCategory().readCategoryForwards(category_1).map(ResolvedEvent::eventRecord).collect(toList()), contains(
                objectWith(EventRecord::streamId, streamId(category_1, "Id1")),
                objectWith(EventRecord::streamId, streamId(category_1, "Id2"))
        ));
    }

    @Test
    public void
    can_continue_reading_from_position_of_category() {
        NewEvent event1 = anEvent();
        NewEvent event4 = anEvent();
        eventSource().writeStream().write(streamId(category_1, "Id1"), singletonList(event1));
        eventSource().writeStream().write(streamId(category_3, "Id1"), singletonList(anEvent()));
        eventSource().writeStream().write(streamId(category_2, "Id1"), singletonList(anEvent()));
        eventSource().writeStream().write(streamId(category_1, "Id2"), singletonList(event4));

        Position position = eventSource().readCategory().readCategoryForwards(category_1).collect(toList()).get(0).position();

        assertThat(eventSource().readCategory().readCategoryForwards(category_1, position).map(ResolvedEvent::eventRecord).collect(toList()), contains(
                objectWith(EventRecord::streamId, streamId(category_1, "Id2"))
        ));
    }

    @Test
    public void
    can_continue_reading_from_position_at_end_of_category() {
        NewEvent event1 = anEvent();
        NewEvent event4 = anEvent();
        eventSource().writeStream().write(streamId(category_1, "Id1"), singletonList(event1));
        eventSource().writeStream().write(streamId(category_3, "Id1"), singletonList(anEvent()));
        eventSource().writeStream().write(streamId(category_2, "Id1"), singletonList(anEvent()));
        eventSource().writeStream().write(streamId(category_1, "Id2"), singletonList(event4));

        Position position = eventSource().readCategory().readCategoryForwards(category_1).reduce((a, b) -> b).get().position();

        assertThat(eventSource().readCategory().readCategoryForwards(category_1, position).collect(toList()), empty());
    }

    @Test public void
    can_read_multiple_categories_in_one_request() {
        eventSource().writeStream().write(streamId(category_1, "Id1"), singletonList(anEvent()));
        eventSource().writeStream().write(streamId(category_1, "Id2"), singletonList(anEvent()));
        eventSource().writeStream().write(streamId(category_2, "Id3"), singletonList(anEvent()));
        eventSource().writeStream().write(streamId(category_1, "Id4"), singletonList(anEvent()));
        eventSource().writeStream().write(streamId(category_3, "Id5"), singletonList(anEvent()));
        eventSource().writeStream().write(streamId(category_2, "Id6"), singletonList(anEvent()));

        List<EventRecord> events = eventSource().readCategory().readCategoriesForwards(Arrays.asList(category_1, category_2), eventSource().readAll().emptyStorePosition()).map(ResolvedEvent::eventRecord).collect(toList());

        assertThat(events, contains(
                objectWith(EventRecord::streamId, streamId(category_1, "Id1")),
                objectWith(EventRecord::streamId, streamId(category_1, "Id2")),
                objectWith(EventRecord::streamId, streamId(category_2, "Id3")),
                objectWith(EventRecord::streamId, streamId(category_1, "Id4")),
                objectWith(EventRecord::streamId, streamId(category_2, "Id6"))
        ));
    }

    @Test
    public void
    can_read_events_backwards_by_category() {
        NewEvent event1 = anEvent();
        NewEvent event4 = anEvent();
        eventSource().writeStream().write(streamId(category_1, "Id1"), singletonList(event1));
        eventSource().writeStream().write(streamId(category_3, "Id1"), singletonList(anEvent()));
        eventSource().writeStream().write(streamId(category_2, "Id1"), singletonList(anEvent()));
        eventSource().writeStream().write(streamId(category_1, "Id2"), singletonList(event4));

        assertThat(eventSource().readCategory().readCategoryBackwards(category_1).map(ResolvedEvent::eventRecord).collect(toList()), contains(
                objectWith(EventRecord::streamId, streamId(category_1, "Id2")),
                objectWith(EventRecord::streamId, streamId(category_1, "Id1"))
        ));
    }

    @Test
    public void
    can_continue_reading_backwards_from_position_of_category() {
        NewEvent event1 = anEvent();
        NewEvent event4 = anEvent();
        eventSource().writeStream().write(streamId(category_1, "Id1"), singletonList(event1));
        eventSource().writeStream().write(streamId(category_3, "Id1"), singletonList(anEvent()));
        eventSource().writeStream().write(streamId(category_2, "Id1"), singletonList(anEvent()));
        eventSource().writeStream().write(streamId(category_1, "Id2"), singletonList(event4));

        Position position = eventSource().readCategory().readCategoryBackwards(category_1).collect(toList()).get(0).position();

        assertThat(eventSource().readCategory().readCategoryBackwards(category_1, position).map(ResolvedEvent::eventRecord).collect(toList()), Matchers.contains(
                objectWith(EventRecord::streamId, streamId(category_1, "Id1"))
        ));
    }

    @Test
    public void
    can_continue_reading_backwards_from_position_at_beginning_of_category() {
        NewEvent event1 = anEvent();
        NewEvent event4 = anEvent();
        eventSource().writeStream().write(streamId(category_1, "Id1"), singletonList(event1));
        eventSource().writeStream().write(streamId(category_3, "Id1"), singletonList(anEvent()));
        eventSource().writeStream().write(streamId(category_2, "Id1"), singletonList(anEvent()));
        eventSource().writeStream().write(streamId(category_1, "Id2"), singletonList(event4));

        Position position = eventSource().readCategory().readCategoryBackwards(category_1).reduce((a, b) -> b).get().position();

        assertThat(eventSource().readCategory().readCategoryBackwards(category_1, position).collect(toList()), empty());
    }

    @Test public void
    writes_consistent_event_numbers_from_multiple_threads() throws InterruptedException {
        StreamId stream = streamId(category_1, "Id1");

        ExecutorService exec = Executors.newFixedThreadPool(4);

        EventStreamWriter writer = eventSource().writeStream();

        range(0, 100).forEach(i -> {
            // Cast to Runnable required only by java9, not 8 or 10
            exec.submit(() -> writer.write(stream, singletonList(anEvent())));
        });

        exec.shutdown();
        exec.awaitTermination(2, SECONDS);

        List<Long> eventNumberWritten = eventSource().readStream().readStreamForwards(stream).map(e -> e.eventRecord().eventNumber()).collect(toList());

        assertThat(eventNumberWritten, is(range(0, 100).boxed().collect(toList())));
    }

    @Test
    public void
    can_read_last_event() {
        eventSource().writeStream().write(stream_1, singletonList(event_1));
        eventSource().writeStream().write(stream_2, singletonList(event_2));
        eventSource().writeStream().write(stream_3, singletonList(event_3));

        EventRecord eventRecord = eventSource().readAll().readLastEvent().map(ResolvedEvent::eventRecord).get();

        assertThat(eventRecord, is(objectWith(EventRecord::streamId, stream_3).and(EventRecord::eventNumber, 0L)));
    }

    @Test
    public void
    can_read_last_event_from_category() {
        eventSource().writeStream().write(stream_1, singletonList(event_1));
        eventSource().writeStream().write(stream_2, singletonList(event_2));
        eventSource().writeStream().write(stream_3, singletonList(event_3));

        EventRecord eventRecord = eventSource().readCategory().readLastEventInCategory(stream_1.category()).map(ResolvedEvent::eventRecord).get();
        assertThat(eventRecord, is(objectWith(EventRecord::streamId, stream_1).and(EventRecord::eventNumber, 0L)));
    }

    @Test
    public void
    can_read_last_event_from_stream() {
        eventSource().writeStream().write(stream_1, singletonList(event_1));
        eventSource().writeStream().write(stream_2, singletonList(event_2));
        eventSource().writeStream().write(stream_3, singletonList(event_3));

        EventRecord eventRecord = eventSource().readStream().readLastEventInStream(stream_2).eventRecord();
        assertThat(eventRecord, is(objectWith(EventRecord::streamId, stream_2).and(EventRecord::eventNumber, 0L)));
    }

    @Test public void
    can_execute_several_write_requests_in_one_operation() {
        eventSource().writeStream().execute(Arrays.asList(
                new StreamWriteRequest(stream_1, singletonList(event_1), OptionalLong.empty()),
                new StreamWriteRequest(stream_2, asList(event_2, event_3), OptionalLong.empty())
        ));

        assertThat(eventSource().readAll().readAllForwards().map(ResolvedEvent::eventRecord).collect(toList()), contains(
                objectWith(EventRecord::streamId, stream_1).and(EventRecord::eventNumber, 0L),
                objectWith(EventRecord::streamId, stream_2).and(EventRecord::eventNumber, 0L),
                objectWith(EventRecord::streamId, stream_2).and(EventRecord::eventNumber, 1L)
        ));
    }

    @Test public void
    when_a_write_request_fails_others_are_still_executed() {
        eventSource().writeStream().write(stream_1, singletonList(event_1));
        eventSource().writeStream().write(stream_1, singletonList(event_2));

        WrongExpectedVersionException exception = null;

        try {
            eventSource().writeStream().execute(Arrays.asList(
                    new StreamWriteRequest(stream_1, singletonList(event_4), OptionalLong.of(0)),
                    new StreamWriteRequest(stream_2, asList(event_5, event_5), OptionalLong.of(-1))
            ));
        } catch (WrongExpectedVersionException e) {
            exception = e;
        }

        assertThat(exception, notNullValue());
        assertThat(eventSource().readAll().readAllForwards().map(ResolvedEvent::eventRecord).collect(toList()), contains(
                objectWith(EventRecord::streamId, stream_1).and(EventRecord::eventNumber, 0L),
                objectWith(EventRecord::streamId, stream_1).and(EventRecord::eventNumber, 1L),
                objectWith(EventRecord::streamId, stream_2).and(EventRecord::eventNumber, 0L),
                objectWith(EventRecord::streamId, stream_2).and(EventRecord::eventNumber, 1L)
        ));
    }

    @Test public void
    cannot_issue_multiple_write_requests_to_the_same_stream() {
        thrown.expectMessage("Duplicate streamId in write request");

        eventSource().writeStream().execute(Arrays.asList(
                new StreamWriteRequest(stream_1, singletonList(event_1), OptionalLong.empty()),
                new StreamWriteRequest(stream_1, singletonList(event_2), OptionalLong.empty())
        ));
    }

    private static Matcher<Instant> shortlyAfter(Instant expected) {
        return new TypeSafeDiagnosingMatcher<Instant>() {
            @Override
            protected boolean matchesSafely(Instant instant, Description description) {
                long seconds = Duration.between(expected, instant).getSeconds();

                description.appendText(" got " + instant);

                return seconds < 1;
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("at most 1s after " + expected);
            }
        };
    }

    static NewEvent anEvent() {
        return newEvent(UUID.randomUUID().toString(), randomData(), randomData());
    }

    protected static String randomCategory() {
        return "stream_" + UUID.randomUUID().toString().replace("-", "");
    }

    protected static byte[] randomData() {
        return ("{\n  \"value\": \"" + UUID.randomUUID() + "\"\n}").getBytes(UTF_8);
    }

    @SafeVarargs
    private static <E> Matcher<Iterable<? extends E>> contains(Matcher<? super E>... itemMatchers) {
        return Matchers.contains(itemMatchers);
    }
}