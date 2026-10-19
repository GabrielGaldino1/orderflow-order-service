package dev.orderflow.order.outbox;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OutboxRepository {

    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public List<OutboxMessage> claimBatch(String claimant, int batchSize, Duration claimTimeout) {
        return jdbc.query("""
                with candidates as (
                    select message_id
                    from outbox_messages
                    where published_at is null
                      and (claimed_at is null or claimed_at < current_timestamp - (? * interval '1 millisecond'))
                    order by created_at
                    for update skip locked
                    limit ?
                )
                update outbox_messages message
                set claimed_by = ?,
                    claimed_at = current_timestamp,
                    last_attempt_at = current_timestamp,
                    attempt_count = message.attempt_count + 1,
                    last_error = null
                from candidates
                where message.message_id = candidates.message_id
                returning message.message_id, message.aggregate_id, message.event_type,
                          message.event_version, message.destination, message.message_key,
                          message.payload, message.created_at, message.attempt_count
                """, (rs, rowNum) -> new OutboxMessage(
                        rs.getObject("message_id", UUID.class),
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getInt("event_version"),
                        rs.getString("destination"),
                        rs.getString("message_key"),
                        rs.getString("payload"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getInt("attempt_count")),
                claimTimeout.toMillis(),
                batchSize,
                claimant);
    }

    public boolean markPublished(UUID messageId, String claimant) {
        return jdbc.update("""
                update outbox_messages
                set published_at = current_timestamp,
                    claimed_by = null,
                    claimed_at = null,
                    last_error = null
                where message_id = ? and claimed_by = ? and published_at is null
                """, messageId, claimant) == 1;
    }

    public int renewClaims(String claimant) {
        return jdbc.update("""
                update outbox_messages
                set claimed_at = current_timestamp
                where claimed_by = ? and published_at is null
                """, claimant);
    }

    public void releaseAfterFailure(UUID messageId, String claimant, String failureType) {
        jdbc.update("""
                update outbox_messages
                set claimed_by = null,
                    claimed_at = null,
                    last_error = ?
                where message_id = ? and claimed_by = ? and published_at is null
                """, failureType, messageId, claimant);
    }

    public Stats stats() {
        return jdbc.queryForObject("""
                select count(*) as backlog_size,
                       coalesce(extract(epoch from (current_timestamp - min(created_at))), 0) as oldest_age_seconds
                from outbox_messages
                where published_at is null
                """, (rs, rowNum) -> new Stats(
                        rs.getLong("backlog_size"),
                        rs.getLong("oldest_age_seconds")));
    }

    public record Stats(long backlogSize, long oldestAgeSeconds) {
    }
}
