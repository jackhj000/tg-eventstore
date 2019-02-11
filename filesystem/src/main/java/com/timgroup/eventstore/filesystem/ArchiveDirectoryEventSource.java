package com.timgroup.eventstore.filesystem;

import com.timgroup.eventstore.api.EventCategoryReader;
import com.timgroup.eventstore.api.EventReader;
import com.timgroup.eventstore.api.EventSource;
import com.timgroup.eventstore.api.EventStreamReader;
import com.timgroup.eventstore.api.EventStreamWriter;
import com.timgroup.eventstore.api.Position;
import com.timgroup.eventstore.api.PositionCodec;
import com.timgroup.eventstore.api.ResolvedEvent;
import com.timgroup.tucker.info.Component;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static java.util.Collections.emptySet;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

public final class ArchiveDirectoryEventSource implements EventSource, EventReader {
    @Nonnull
    private final Path archiveDirectory;

    public ArchiveDirectoryEventSource(@Nonnull Path archiveDirectory) {
        this.archiveDirectory = requireNonNull(archiveDirectory);
    }

    @Nonnull
    @Override
    public EventReader readAll() {
        return this;
    }

    @Nonnull
    @Override
    public Stream<ResolvedEvent> readAllForwards(Position positionExclusive) {
        return archiveFilesStartingFrom((ArchiveDirectoryPosition) positionExclusive)
                .stream()
                .flatMap(positionFilePath -> {
                    String positionFileName = positionFilePath.getFileName().toString();
                    String archiveName = positionFileName.replaceAll("\\.position\\.txt$", ".cpio");
                    Path archivePath = positionFilePath.resolveSibling(archiveName);
                    return new ArchiveEventReader(archivePath)
                            .readAllForwards(((ArchiveDirectoryPosition) positionExclusive).getPosition())
                            .map(re -> re.eventRecord().toResolvedEvent(new ArchiveDirectoryPosition(archiveName, (ArchivePosition) re.position())));
                });
    }

    @Nonnull
    @Override
    public Position emptyStorePosition() {
        return ArchiveDirectoryPosition.EMPTY;
    }

    @Nonnull
    @Override
    public EventCategoryReader readCategory() {
        throw new UnsupportedOperationException();
    }

    @Nonnull
    @Override
    public EventStreamReader readStream() {
        throw new UnsupportedOperationException();
    }

    @Nonnull
    @Override
    public EventStreamWriter writeStream() {
        throw new UnsupportedOperationException();
    }

    @Nonnull
    @Override
    public PositionCodec positionCodec() {
        return ArchiveDirectoryPosition.CODEC;
    }

    @Nonnull
    @Override
    public Collection<Component> monitoring() {
        return emptySet();
    }

    private List<Path> archiveFilesStartingFrom(ArchiveDirectoryPosition startExclusive) {
        Predicate<? super Path> fileFilter;
        if (startExclusive.equals(ArchiveDirectoryPosition.EMPTY)) {
            fileFilter = p -> true;
        }
        else {
            String minFilename = startExclusive.getPosition().getFilename() + "~";
            fileFilter = p -> p.getFileName().toString().compareTo(minFilename) >= 0;
        }
        return positionFiles().stream().filter(fileFilter).collect(toList());
    }

    private List<Path> positionFiles() {
        try (Stream<Path> paths = Files.list(archiveDirectory)) {
            return paths.filter(p -> p.getFileName().toString().endsWith(".position.txt")).sorted().collect(toList());
        } catch (IOException e) {
            throw new WrappedIOException(e);
        }
    }
}
