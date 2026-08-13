package io.sentinel.ingest.api.dto;

import java.util.List;

/**
 * Per-item outcome for an ingest call.
 *
 * <p>A duplicate is reported as {@code DUPLICATE} with HTTP 202 rather than an error: replaying a
 * batch after a network timeout is normal client behaviour, not a fault.
 */
public record IngestResponse(int accepted, int duplicates, int rejected, List<Item> items) {

    public record Item(String eventId, Outcome outcome, String reason) {

        public static Item accepted(String eventId) {
            return new Item(eventId, Outcome.ACCEPTED, null);
        }

        public static Item duplicate(String eventId) {
            return new Item(eventId, Outcome.DUPLICATE, "Already ingested within the idempotency window");
        }

        public static Item rejected(String eventId, String reason) {
            return new Item(eventId, Outcome.REJECTED, reason);
        }
    }

    public enum Outcome {
        ACCEPTED,
        DUPLICATE,
        REJECTED
    }

    public static IngestResponse from(List<Item> items) {
        int accepted = (int)
                items.stream().filter(i -> i.outcome() == Outcome.ACCEPTED).count();
        int duplicates = (int)
                items.stream().filter(i -> i.outcome() == Outcome.DUPLICATE).count();
        int rejected = (int)
                items.stream().filter(i -> i.outcome() == Outcome.REJECTED).count();
        return new IngestResponse(accepted, duplicates, rejected, items);
    }
}
