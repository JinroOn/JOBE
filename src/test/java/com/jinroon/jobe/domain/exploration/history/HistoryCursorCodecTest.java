package com.jinroon.jobe.domain.exploration.history;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class HistoryCursorCodecTest {
    private final ObjectMapper mapper=new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final HistoryCursorCodec codec=new HistoryCursorCodec(mapper,"synthetic-history-secret-32-bytes-long");
    private final HistoryQuery query=new HistoryQuery("ALL","ALL",20,null);
    private HistoryCursorCodec.State state(int version,int rank,long id,long legacy,long exploration) {
        return new HistoryCursorCodec.State(version,7,"sessions","ALL","ALL",20,legacy,exploration,LocalDateTime.of(2026,11,15,12,0,0,123456000),rank,id);
    }
    @Test void roundTripPreservesMicrosecondsAndFullLongIds() {
        var state=state(1,1,Long.MAX_VALUE,0,Long.MAX_VALUE);
        assertThat(codec.decode(codec.encode(state),7,"sessions",query)).isEqualTo(state);
    }
    @Test void rotationInvalidatesOldCursorsWithoutPrintingOrExposingSecrets() {
        String token=codec.encode(state(1,0,1,3,0));
        var rotated=new HistoryCursorCodec(mapper,"different-synthetic-secret-32-bytes-long");
        assertThatThrownBy(()->rotated.decode(token,7,"sessions",query)).isInstanceOf(ExplorationApiException.class);
    }
    @Test void invalidVersionsRanksAndFencesAreRejectedEvenWhenSigned() {
        for(var state:new HistoryCursorCodec.State[]{state(2,0,1,3,0),state(1,2,1,3,3),state(1,0,4,3,0),state(1,1,1,3,0),state(1,0,0,3,0)})
            assertThatThrownBy(()->codec.decode(codec.encode(state),7,"sessions",query)).isInstanceOf(ExplorationApiException.class);
    }
    @Test void paddingOrInjectedSignatureCannotBecomeAnAcceptedCursor() {
        String token=codec.encode(state(1,0,1,3,0));
        assertThatThrownBy(()->codec.decode(token+"=",7,"sessions",query)).isInstanceOf(ExplorationApiException.class);
        assertThatThrownBy(()->codec.decode(token+".extra",7,"sessions",query)).isInstanceOf(ExplorationApiException.class);
    }
}
