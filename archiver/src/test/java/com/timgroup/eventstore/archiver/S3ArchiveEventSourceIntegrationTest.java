package com.timgroup.eventstore.archiver;

import com.amazonaws.services.s3.AmazonS3;
import com.codahale.metrics.MetricRegistry;
import com.timgroup.config.ConfigLoader;
import com.timgroup.eventstore.api.EventRecordMatcher;
import com.timgroup.eventstore.api.EventSource;
import com.timgroup.eventstore.api.NewEvent;
import com.timgroup.eventstore.api.ResolvedEvent;
import com.timgroup.eventstore.api.ResolvedEventMatcher;
import com.timgroup.eventstore.api.StreamId;
import com.timgroup.eventstore.archiver.monitoring.S3ArchiveConnectionComponent;
import com.timgroup.eventstore.memory.InMemoryEventSource;
import com.timgroup.eventstore.memory.JavaInMemoryEventStore;
import com.timgroup.eventsubscription.SubscriptionBuilder;
import com.timgroup.eventsubscription.healthcheck.InitialCatchupFuture;
import com.timgroup.remotefilestorage.s3.S3ClientFactory;
import com.timgroup.remotefilestorage.s3.S3DownloadableStorage;
import com.timgroup.remotefilestorage.s3.S3DownloadableStorageWithoutDestinationFile;
import com.timgroup.remotefilestorage.s3.S3ListableStorage;
import com.timgroup.remotefilestorage.s3.S3UploadableStorage;
import com.timgroup.remotefilestorage.s3.S3UploadableStorageForInputStream;
import com.timgroup.tucker.info.Component;
import com.timgroup.tucker.info.Report;
import com.timgroup.tucker.info.Status;
import com.youdevise.testutils.matchers.JOptionalMatcher;
import org.hamcrest.FeatureMatcher;
import org.hamcrest.Matcher;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Properties;

import static com.amazonaws.SDKGlobalConfiguration.ACCESS_KEY_SYSTEM_PROPERTY;
import static com.timgroup.eventstore.api.EventRecordMatcher.anEventRecord;
import static com.timgroup.eventstore.api.NewEvent.newEvent;
import static com.timgroup.eventstore.api.ResolvedEventMatcher.aResolvedEvent;
import static com.timgroup.eventstore.api.StreamId.streamId;
import static com.youdevise.testutils.matchers.JOptionalMatcher.isPresent;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;

public class S3ArchiveEventSourceIntegrationTest extends S3IntegrationTest {
    private AmazonS3 amazonS3;
    private String bucketName;
    private String eventStoreId;
    private String testClassName = getClass().getSimpleName();

    private final Instant fixedEventTimestamp = Instant.parse("2019-03-19T20:43:00.044Z");
    private final Clock fixedClock = Clock.fixed(fixedEventTimestamp, ZoneId.systemDefault());
    private final BatchingPolicy twoEventsPerBatch =  BatchingPolicy.fixedNumberOfEvents(2);
    private final MetricRegistry metricRegistry = new MetricRegistry();

    @Before
    public void
    configure() {
        Properties properties = ConfigLoader.loadConfig(S3_PROPERTIES_FILE);
        amazonS3 = new S3ClientFactory().fromProperties(properties);
        bucketName = properties.getProperty("tg.eventstore.archive.bucketName");
        eventStoreId = uniqueEventStoreId(testClassName);
    }

    @Test public void
    monitoring_includes_component_with_archive_metadata_in_label() throws Exception {
        EventSource s3ArchiveEventSource = createS3ArchiveEventSource();

        S3ArchiveConnectionComponent connectionComponent = getConnectionComponent(s3ArchiveEventSource);
        assertThat(connectionComponent.getLabel(), allOf(containsString(eventStoreId), containsString(bucketName)));
    }

    @Test public void
    monitoring_includes_component_that_is_critical_when_it_cannot_connect_to_s3_archive() throws IOException {
        Properties properties = ConfigLoader.loadConfig(S3_PROPERTIES_FILE);
        properties.setProperty("s3.region", "us-gov-east-1");

        amazonS3 = new S3ClientFactory().fromProperties(properties);
        EventSource s3ArchiveEventSource = createS3ArchiveEventSource();

        Component connectionComponent = getConnectionComponent(s3ArchiveEventSource);

        Report report = connectionComponent.getReport();

        assertThat(report.getStatus(), equalTo(Status.CRITICAL));
        assertThat(report.getValue().toString(), containsString("AmazonS3Exception"));
    }

    @After
    public void clearAccessKeyPropertyOnlyUsedForTestingLackOfAccess() {
        System.clearProperty(ACCESS_KEY_SYSTEM_PROPERTY);
    }

    @Test public void
    monitoring_includes_component_that_is_critical_when_connects_to_s3_archive_but_event_store_does_not_exist() throws IOException {
        EventSource s3ArchiveEventSource = createS3ArchiveEventSource();

        Component connectionComponent = getConnectionComponent(s3ArchiveEventSource);

        Report report = connectionComponent.getReport();

        assertThat(report.getStatus(), equalTo(Status.CRITICAL));
        assertThat(report.getValue().toString(), allOf(
                containsString("Successfully connected to S3 EventStore"),
                containsString("no EventStore with ID='" + eventStoreId + "' exists")));
    }

    @Test public void
    monitoring_includes_component_that_is_okay_and_contains_max_position_when_it_can_connect_to_archive() throws Exception {
        EventSource liveEventSource = new InMemoryEventSource(new JavaInMemoryEventStore(fixedClock));
        StreamId anyStream = streamId(randomCategory(), "1");
        NewEvent anyEvent = newEvent("type-A", randomData(), randomData());
        liveEventSource.writeStream().write(anyStream, asList(anyEvent, anyEvent, anyEvent, anyEvent));
        successfullyArchiveUntilCaughtUp(fixedClock, liveEventSource);

        EventSource s3ArchiveEventSource = createS3ArchiveEventSource();


        Component connectionComponent = getConnectionComponent(s3ArchiveEventSource);

        Report report = connectionComponent.getReport();

        assertThat(report.getValue().toString(), allOf(
                containsString("Successfully connected to S3 EventStore"),
                containsString("position=4")));
        assertThat(report.getStatus(), equalTo(Status.OK));
    }

    @Test public void
    is_not_confused_by_matching_prefix_of_a_distinct_event_store() throws Exception {
        StreamId anyStream = streamId(randomCategory(), "1");
        NewEvent anyEvent = newEvent("type-A", randomData(), randomData());

        EventSource otherLiveEventSource = new InMemoryEventSource(new JavaInMemoryEventStore(fixedClock));
        otherLiveEventSource.writeStream().write(anyStream, asList(anyEvent, anyEvent, anyEvent, anyEvent));

        EventSource thisLiveEventSource = new InMemoryEventSource(new JavaInMemoryEventStore(fixedClock));
        thisLiveEventSource.writeStream().write(anyStream, asList(anyEvent, anyEvent, anyEvent, anyEvent,
                newEvent("type-B", randomData(), randomData()),
                newEvent("type-C", randomData(), randomData())));

        String thisEventStore = this.eventStoreId;
        String someOtherEventStoreWithSamePrefix = this.eventStoreId + "_event_store_id_suffix";

        successfullyArchiveUntilCaughtUp(fixedClock, thisLiveEventSource, thisEventStore);
        successfullyArchiveUntilCaughtUp(fixedClock, otherLiveEventSource, someOtherEventStoreWithSamePrefix);

        EventSource s3ArchiveEventSource = this.createS3ArchiveEventSource(thisEventStore);

        Component connectionComponent = getConnectionComponent(s3ArchiveEventSource);

        Report report = connectionComponent.getReport();

        assertThat(report.getValue().toString(), allOf(
                containsString("Successfully connected to S3 EventStore"),
                containsString("position=6")));
        assertThat(report.getStatus(), equalTo(Status.OK));

        assertThat(s3ArchiveEventSource.readAll().readLastEvent(), isPresent(allOf(
                withPosition(new S3ArchivePosition(6)),
                withEventType("type-C")
        )));

        assertThat(s3ArchiveEventSource.readAll().readAllForwards().collect(toList()), hasSize(6));

    }

    private Matcher<ResolvedEvent> withPosition(S3ArchivePosition s3ArchivePosition) {
        return new FeatureMatcher<ResolvedEvent, S3ArchivePosition>(equalTo(s3ArchivePosition), "position", "") {
            @Override
            protected S3ArchivePosition featureValueOf(ResolvedEvent actual) {
                return (S3ArchivePosition) actual.position();
            }
        };
    }

    private Matcher<ResolvedEvent> withEventType(String eventType) {
        return new FeatureMatcher<ResolvedEvent, String>(equalTo(eventType), "event_type", "") {
            @Override
            protected String featureValueOf(ResolvedEvent actual) {
                return actual.eventRecord().eventType();
            }
        };
    }


    private S3ArchiveConnectionComponent getConnectionComponent(EventSource s3ArchiveEventSource) {
        Collection<Component> monitoring = s3ArchiveEventSource.monitoring();
        assertThat(monitoring, hasSize(1));
        Component connectionComponent = monitoring.iterator().next();
        assertThat(connectionComponent, instanceOf(S3ArchiveConnectionComponent.class));
        return (S3ArchiveConnectionComponent) connectionComponent;
    }

    private EventSource createS3ArchiveEventSource() throws IOException {
        return createS3ArchiveEventSource(this.eventStoreId);
    }

    private EventSource createS3ArchiveEventSource(String eventStoreId) throws IOException {
        return new S3ArchivedEventSource(createListableStorage(), createDownloadableStorage(), bucketName, eventStoreId);
    }

    private S3DownloadableStorageWithoutDestinationFile createDownloadableStorage() throws IOException {
        return new S3DownloadableStorageWithoutDestinationFile(
                new S3DownloadableStorage(amazonS3, Files.createTempDirectory(testClassName), bucketName),
                amazonS3, bucketName);
    }

    private S3ListableStorage createListableStorage() {
        int maxKeysInListingToTriggerPagingBehaviour = 1;
        return new S3ListableStorage(amazonS3, bucketName, maxKeysInListingToTriggerPagingBehaviour);
    }

    private S3Archiver successfullyArchiveUntilCaughtUp(Clock clock, EventSource liveEventSource) {
        return successfullyArchiveUntilCaughtUp(clock, liveEventSource, this.eventStoreId);
    }

    private S3Archiver successfullyArchiveUntilCaughtUp(Clock clock, EventSource liveEventSource, String givenEventStoreId) {
        InitialCatchupFuture catchupFuture = new InitialCatchupFuture();
        SubscriptionBuilder subscription = SubscriptionBuilder.eventSubscription("test")
                .withRunFrequency(Duration.of(1, ChronoUnit.MILLIS))
                .publishingTo(catchupFuture);

        S3ListableStorage listableStorage = createListableStorage();
        S3Archiver archiver = S3Archiver.newS3Archiver(liveEventSource, createUploadableStorage(), givenEventStoreId, subscription,
                twoEventsPerBatch, new S3ArchiveMaxPositionFetcher(listableStorage, givenEventStoreId),
                testClassName, metricRegistry, S3Archiver.DEFAULT_MONITORING_PREFIX, clock);

        archiver.start();

        completeOrFailAfter(catchupFuture, Duration.ofSeconds(5L));

        return archiver;
    }

    private S3UploadableStorageForInputStream createUploadableStorage() {
        return new S3UploadableStorageForInputStream(new S3UploadableStorage(amazonS3, bucketName), amazonS3, bucketName);
    }

}
