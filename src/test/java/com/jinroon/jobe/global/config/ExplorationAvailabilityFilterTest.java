package com.jinroon.jobe.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinroon.jobe.global.security.ExplorationAvailabilityFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ExplorationAvailabilityFilterTest {
    @Test
    void enablingFeaturePassesToAuthenticationAndDoesNotPretendToImplementEndpoint() throws Exception {
        var request=new MockHttpServletRequest("GET","/api/exploration/v1/sessions");
        var response=new MockHttpServletResponse();
        boolean[] chained={false};
        new ExplorationAvailabilityFilter(true,new ObjectMapper()).doFilter(request,response,(a,b)->chained[0]=true);
        assertThat(chained[0]).isTrue();
    }
    @Test
    void disabledFeatureDoesNotMatchSimilarPrefixOrOtherApi() throws Exception {
        for (String uri:new String[]{"/api/exploration/v10/sessions","/api/majors"}) {
            boolean[] chained={false};
            new ExplorationAvailabilityFilter(false,new ObjectMapper()).doFilter(new MockHttpServletRequest("GET",uri),new MockHttpServletResponse(),(a,b)->chained[0]=true);
            assertThat(chained[0]).isTrue();
        }
    }
}
