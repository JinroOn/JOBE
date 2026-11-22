package com.jinroon.jobe.domain.exploration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import com.jinroon.jobe.domain.exploration.entity.ExplorationResult;
import com.jinroon.jobe.domain.exploration.service.ExplorationResultStore;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class ExplorationResultListingTest {
    @Test void databaseFailureIsNotDisguisedAsAnUnavailableResult() {
        var store = mock(ExplorationResultStore.class, CALLS_REAL_METHODS);
        var result = mock(ExplorationResult.class);
        var failure = new DataAccessResourceFailureException("synthetic database outage");
        doThrow(failure).when(store).verifiedSnapshot(result);
        assertThatThrownBy(() -> store.availableForListing(result)).isSameAs(failure);
    }

    @Test void unrelatedApiFailuresAreNotDisguisedAsIntegrityFailures() {
        var store = mock(ExplorationResultStore.class, CALLS_REAL_METHODS);
        var result = mock(ExplorationResult.class);
        var failure = new ExplorationApiException(503, "NO_ACTIVE_CATALOG", "synthetic");
        doThrow(failure).when(store).verifiedSnapshot(result);
        assertThatThrownBy(() -> store.availableForListing(result)).isSameAs(failure);
    }
}
