package com.togethermarket.recommendation.service.customer;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.togethermarket.recommendation.controller.CustomerReadController;
import com.togethermarket.recommendation.dto.response.*;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RecommendationLocationsJsonTest {
  @Test
  void missingCoordinatesAreExplicitNullWithStringIdsAndStatus() throws Exception {
    var service = mock(CustomerReadService.class);
    var unavailable = new CustomerStoreSummary("21", "점포", "카페", "주소", null, null, "번호");
    var available =
        new CustomerStoreSummary("22", "점포2", "카페", "주소2", BigDecimal.ONE, BigDecimal.TEN, "번호");
    when(service.locations("5", 2L))
        .thenReturn(
            new RecommendationLocationsResponse(
                "5", List.of(unavailable, available), null, "STRAIGHT_LINE", "UNAVAILABLE"));
    var mvc =
        MockMvcBuilders.standaloneSetup(new CustomerReadController(service, request -> 2L)).build();
    mvc.perform(get("/recommendations/5/locations"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.recommendation_candidate_id").value("5"))
        .andExpect(jsonPath("$.data.distance_meters").value(nullValue()))
        .andExpect(jsonPath("$.data.location_status").value("UNAVAILABLE"))
        .andExpect(jsonPath("$.data.stores[0].latitude").value(nullValue()))
        .andExpect(jsonPath("$.data.stores[0].longitude").value(nullValue()))
        .andExpect(jsonPath("$.data.stores[1].id").value("22"));
  }
}
