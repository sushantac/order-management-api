package com.company.orderapi.domain.idempotency;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * PR #24 - stores and replays idempotent write results.
 *
 * <p>The service is deliberately small: lookup by key, and persist a result.
 * The caller (controller) decides which writes are idempotent-guarded and how
 * the stored response is replayed.
 */
@Service
public class IdempotencyService {

    private final IdempotencyKeyRepository store;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyKeyRepository store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Optional<StoredResponse> find(String key) {
        return store.findByIdempotencyKey(key)
                .map(r -> new StoredResponse(r.getResponseStatus(), r.getResponseBody()));
    }

    @Transactional
    public void record(String key, String method, String path, int status, Object body) {
        store.saveAndFlush(IdempotencyRecord.of(key, method, path, status, toJson(body)));
    }

    /** Replay payload: parse stored JSON into an {@link JsonNode}. */
    public JsonNode parseStoredBody(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("Stored idempotent response is corrupt", e);
        }
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise idempotent response", e);
        }
    }

    /** Minimal immutable value returned to the controller on a replay. */
    public record StoredResponse(int status, String body) {
    }
}
