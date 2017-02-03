package com.timgroup.eventstore.mysql.legacy;

import com.timgroup.eventstore.api.*;
import com.timgroup.eventstore.mysql.ConnectionProvider;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.Stream;

import static java.util.stream.StreamSupport.stream;

public final class LegacyMysqlEventReader implements EventReader, EventStreamReader, EventCategoryReader {

    private final ConnectionProvider connectionProvider;
    private final String tableName;
    private final StreamId pretendStreamId;
    private final int batchSize;

    public LegacyMysqlEventReader(ConnectionProvider connectionProvider, String tableName, StreamId pretendStreamId, int batchSize) {
        this.connectionProvider = connectionProvider;
        this.tableName = tableName;
        this.pretendStreamId = pretendStreamId;
        this.batchSize = batchSize;
    }

    @Override
    public Position emptyStorePosition() {
        return LegacyMysqlEventPosition.fromLegacyVersion(0);
    }

    @Override
    public Position emptyCategoryPosition(String category) {
        return emptyStorePosition();
    }

    @Override
    public Stream<ResolvedEvent> readAllForwards() {
        return readAllForwards(emptyStorePosition());
    }

    @Override
    public Stream<ResolvedEvent> readAllForwards(Position positionExclusive) {
        return stream(
                new LegacyMysqlEventSpliterator(
                    connectionProvider,
                    batchSize,
                    tableName,
                    pretendStreamId,
                    (LegacyMysqlEventPosition)positionExclusive,
                    false
                ),
                false
        );
    }

    @Override
    public Stream<ResolvedEvent> readStreamForwards(StreamId streamId, long eventNumber) {
        if (!streamId.equals(pretendStreamId)) {
            throw new IllegalArgumentException("Cannot read " + streamId + " from legacy store");
        }
        ensureStreamExists(streamId);
        return readAllForwards(LegacyMysqlEventPosition.fromEventNumber(eventNumber));
    }

    @Override
    public Stream<ResolvedEvent> readCategoryForwards(String category, Position positionExclusive) {
        if (!category.equals(pretendStreamId.category())) {
            throw new IllegalArgumentException("Cannot read " + category + " from legacy store");
        }
        return readAllForwards(positionExclusive);
    }

    @Override
    public Stream<ResolvedEvent> readAllBackwards() {
        return readAllBackwards(LegacyMysqlEventPosition.fromLegacyVersion(Long.MAX_VALUE));
    }

    @Override
    public Stream<ResolvedEvent> readAllBackwards(Position positionExclusive) {
        return stream(
                new LegacyMysqlEventSpliterator(
                        connectionProvider,
                        batchSize,
                        tableName,
                        pretendStreamId,
                        (LegacyMysqlEventPosition)positionExclusive,
                        true
                ),
                false
        );
    }

    @Override
    public Stream<ResolvedEvent> readCategoryBackwards(String category) {
        return readCategoryBackwards(category, LegacyMysqlEventPosition.fromLegacyVersion(Long.MAX_VALUE));
    }

    @Override
    public Stream<ResolvedEvent> readCategoryBackwards(String category, Position positionExclusive) {
        if (!category.equals(pretendStreamId.category())) {
            throw new IllegalArgumentException("Cannot read " + category + " from legacy store");
        }
        return readAllBackwards(positionExclusive);
    }

    @Override
    public Stream<ResolvedEvent> readStreamBackwards(StreamId streamId) {
        return readStreamBackwards(streamId, LegacyMysqlEventPosition.fromLegacyVersion(Long.MAX_VALUE));
    }

    @Override
    public Stream<ResolvedEvent> readStreamBackwards(StreamId streamId, long eventNumber) {
        return readStreamBackwards(streamId, LegacyMysqlEventPosition.fromEventNumber(eventNumber));
    }

    private Stream<ResolvedEvent> readStreamBackwards(StreamId streamId, LegacyMysqlEventPosition position) {
        if (!streamId.equals(pretendStreamId)) {
            throw new IllegalArgumentException("Cannot read " + streamId + " from legacy store");
        }
        ensureStreamExists(streamId);
        return readAllBackwards(position);
    }

    private void ensureStreamExists(StreamId streamId) throws NoSuchStreamException {
        try (Connection connection = connectionProvider.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(String.format("select version from %s limit 1", tableName))
        ) {
            if (!resultSet.first()) {
                throw new NoSuchStreamException(streamId);
            }
        } catch (SQLException e) {
            throw new RuntimeException(String.format("Error checking whether stream '%s' exists", streamId), e);
        }
    }
}