package com.timgroup.eventstore.stitching;

import com.timgroup.eventstore.api.EventReader;
import com.timgroup.eventstore.api.EventSource;
import com.timgroup.eventstore.api.Position;
import com.timgroup.eventstore.api.PositionCodec;
import com.timgroup.eventstore.api.ResolvedEvent;

import javax.annotation.CheckReturnValue;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

@ParametersAreNonnullByDefault
public final class BackfillStitchingEventReader implements EventReader {

    private final StitchedPosition emptyStorePosition;
    private final EventReader backfill;
    private final EventReader live;

    public BackfillStitchingEventReader(EventSource backfill, EventSource live, Position liveCutoffStartPosition) {
        this(backfill.readAll(), live.readAll(), liveCutoffStartPosition);
    }

    public BackfillStitchingEventReader(EventReader backfill, EventReader live, Position liveCutoffStartPosition) {
        this.backfill = backfill;
        this.live = requireNonNull(live);
        this.emptyStorePosition = new StitchedPosition(backfill.emptyStorePosition(), liveCutoffStartPosition);
    }

    @Nonnull
    @Override
    public Position emptyStorePosition() {
        return emptyStorePosition;
    }

    @Nonnull
    @Override
    public PositionCodec storePositionCodec() {
        return StitchedPosition.codec(backfill.storePositionCodec(), live.storePositionCodec());
    }

    @Nonnull
    @CheckReturnValue
    @Override
    public Stream<ResolvedEvent> readAllForwards(Position positionExclusive) {
        StitchedPosition stitchedPosition = (StitchedPosition) positionExclusive;
        if (stitchedPosition.isInBackfill(emptyStorePosition)) {
            return BackfillStitchingEventForwardsSpliterator.stitchedStreamFrom(
                    backfill.readAllForwards(stitchedPosition.backfillPosition),
                    live.readAllForwards(stitchedPosition.livePosition),
                    stitchedPosition
            );
        } else {
            return BackfillStitchingEventForwardsSpliterator.stitchedStreamFrom(
                    Stream.empty(),
                    live.readAllForwards(stitchedPosition.livePosition),
                    stitchedPosition
            );
        }
    }

    @Override
    public String toString() {
        return "BackfillStitchingEventReader{" +
                "emptyStorePosition=" + emptyStorePosition +
                ", backfill=" + backfill +
                ", live=" + live +
                '}';
    }
}
