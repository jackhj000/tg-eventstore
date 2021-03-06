package com.timgroup.eventstore.merging;

import com.timgroup.eventstore.api.EventCategoryReader;
import com.timgroup.eventstore.api.EventReader;
import com.timgroup.eventstore.api.Position;
import com.timgroup.eventstore.api.PositionCodec;
import com.timgroup.eventstore.api.ResolvedEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

public final class NamedReaderWithCodec {
    final String name;
    final Function<? super Position, ? extends Stream<ResolvedEvent>> reader;
    final PositionCodec codec;
    final Position startingPosition;

    public NamedReaderWithCodec(String name, EventReader reader) {
        this(name, reader, reader.storePositionCodec(), reader.emptyStorePosition());
    }

    public NamedReaderWithCodec(String name, EventReader reader, PositionCodec codec) {
        this(name, reader, codec, reader.emptyStorePosition());
    }

    public NamedReaderWithCodec(String name, EventReader reader, Position startingPosition) {
        this(name, reader::readAllForwards, reader.storePositionCodec(), startingPosition);
    }

    public NamedReaderWithCodec(String name, EventReader reader, PositionCodec codec, Position startingPosition) {
        this(name, reader::readAllForwards, codec, startingPosition);
    }

    public NamedReaderWithCodec(String name, Function<? super Position, ? extends Stream<ResolvedEvent>> reader, PositionCodec codec, Position startingPosition) {
        checkNotNull(name, "name cannot be null");

        final String candidateName = name.trim();
        checkArgument(!candidateName.isEmpty(), "name cannot be empty");

        this.name = candidateName.intern();
        this.reader = reader;
        this.codec = codec;
        this.startingPosition = startingPosition;
    }

    public static NamedReaderWithCodec fromEventReader(String name, EventReader eventReader) {
        return fromEventReader(name, eventReader, eventReader.emptyStorePosition());
    }

    public static NamedReaderWithCodec fromEventReader(String name, EventReader eventReader, String startingPosition) {
        return fromEventReader(name, eventReader, eventReader.storePositionCodec().deserializePosition(startingPosition));
    }

    public static NamedReaderWithCodec fromEventReader(String name, EventReader eventReader, Position startingPosition) {
        return new NamedReaderWithCodec(name, eventReader, eventReader.storePositionCodec(), startingPosition);
    }

    public static NamedReaderWithCodec fromEventCategoryReader(String name, EventCategoryReader eventCategoryReader, String category) {
        return fromEventCategoryReader(name, eventCategoryReader, category, eventCategoryReader.emptyCategoryPosition(category));
    }

    public static NamedReaderWithCodec fromEventCategoryReader(String name, EventCategoryReader eventCategoryReader, String category, String startingPosition) {
        return fromEventCategoryReader(name, eventCategoryReader, category, eventCategoryReader.categoryPositionCodec(category).deserializePosition(startingPosition));
    }

    public static NamedReaderWithCodec fromEventCategoryReader(String name, EventCategoryReader eventCategoryReader, String category, Position startingPosition) {
        return new NamedReaderWithCodec(name, pos -> eventCategoryReader.readCategoryForwards(category, pos), eventCategoryReader.categoryPositionCodec(category), startingPosition);
    }

    public static NamedReaderWithCodec selectedCategories(String name, EventReader reader, EventCategoryReader categoryReader, Collection<String> categories) {
        return selectedCategories(name, reader, categoryReader, categories, reader.emptyStorePosition());
    }

    public static NamedReaderWithCodec selectedCategories(String name, EventReader reader, EventCategoryReader categoryReader, Collection<String> categories, String startingPosition) {
        return selectedCategories(name, reader, categoryReader, categories, reader.storePositionCodec().deserializePosition(startingPosition));
    }

    public static NamedReaderWithCodec selectedCategories(String name, EventReader reader, EventCategoryReader categoryReader, Collection<String> categories, Position startingPosition) {
        List<String> categoryList = new ArrayList<>(categories);
        return new NamedReaderWithCodec(name, pos -> categoryReader.readCategoriesForwards(categoryList, pos), reader.storePositionCodec(), startingPosition);
    }

    @Override
    public String toString() {
        return "NamedReaderWithCodec{" +
                "name='" + name + '\'' +
                ", codec=" + codec +
                ", startingPosition=" + startingPosition +
                '}';
    }
}
