package com.jinroon.jobe.domain.exploration.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Signed navigation state, never an authentication credential. Bound to user, view and filters. */
@Component
public class HistoryCursorCodec {
    private final ObjectMapper mapper;
    private final byte[] key;
    public record State(int version, long userId, String view, String type, String status, int limit,
                        long legacyFence, long explorationFence, LocalDateTime sortAt, int rank, long id) {}

    public HistoryCursorCodec(ObjectMapper mapper, @Value("${jwt.secret}") String jwtSecret) {
        this.mapper = mapper;
        this.key = mac(jwtSecret.getBytes(StandardCharsets.UTF_8), "JOBE/unified-history/cursor/v1".getBytes(StandardCharsets.UTF_8));
    }

    public String encode(State state) {
        try {
            String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(state));
            return payload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(key, payload.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.io.IOException e) { throw new IllegalStateException("Cannot encode history cursor", e); }
    }

    public State decode(String cursor, long userId, String view, HistoryQuery query) {
        try {
            if (cursor.length() > 2048 || !cursor.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) throw invalid();
            String[] parts = cursor.split("\\.");
            byte[] signature = Base64.getUrlDecoder().decode(parts[1]);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(signature).equals(parts[1])) throw invalid();
            if (!MessageDigest.isEqual(signature, mac(key, parts[0].getBytes(StandardCharsets.US_ASCII)))) throw invalid();
            State state = mapper.readValue(Base64.getUrlDecoder().decode(parts[0]), State.class);
            if (state.version() != 1 || state.userId() != userId || !view.equals(state.view())
                    || !query.type().equals(state.type()) || !query.status().equals(state.status()) || query.limit() != state.limit()
                    || state.legacyFence() < 0 || state.explorationFence() < 0 || state.sortAt() == null
                    || state.rank() < 0 || state.rank() > 1 || state.id() <= 0
                    || state.id() > (state.rank() == 0 ? state.legacyFence() : state.explorationFence())) throw invalid();
            return state;
        } catch (java.io.IOException | IllegalArgumentException e) { throw invalid(); }
    }

    private static byte[] mac(byte[] key, byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256")); return mac.doFinal(payload);
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    private static ExplorationApiException invalid() {
        return new ExplorationApiException(400, "INVALID_CURSOR", "커서가 올바르지 않습니다. 같은 조건으로 첫 페이지부터 조회해 주세요.");
    }
}
