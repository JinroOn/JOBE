package com.jinroon.jobe.domain.exploration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class ExplorationShareDisabledApiTest extends CompletedExplorationTestSupport {
    @Test void sharingRemainsDisabledByDefaultWithoutCreatingStateOrRequests()throws Exception {
        long rid=completedResult(true);String path="/api/exploration/v1/results/"+rid+"/share";
        assertThat(response(get(path).header("Authorization",owner),200).path("available").asBoolean()).isFalse();
        assertThat(response(post(path).header("Authorization",owner).header("Idempotency-Key","disabled").contentType(MediaType.APPLICATION_JSON).content("{\"expectedShareRevision\":0}"),503).path("code").asText()).isEqualTo("SHARING_UNAVAILABLE");
        response(get("/api/exploration/v1/shared-results/S1_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"),404);
        assertThat(count("exploration_share_state","result_id=?",rid)).isZero();assertThat(count("exploration_idempotency","user_id=? AND operation='SHARE_ISSUE'",OWNER)).isZero();
    }
}
