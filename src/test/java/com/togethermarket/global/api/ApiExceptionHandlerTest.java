package com.togethermarket.global.api;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import tools.jackson.databind.json.JsonMapper;

class ApiExceptionHandlerTest {
  MockMvc mvc;

  @RestController
  static class ProbeController {
    @PostMapping("/probe")
    void create(@Valid @RequestBody Probe input) {}
  }

  record Probe(
      @JsonProperty("customer_name") @NotBlank String customerName, @Valid List<Pickup> pickups) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  record Pickup(
      @NotBlank String requestedPickupAt, @JsonProperty("store_ref") @NotBlank String storeId) {}

  @BeforeEach
  void setup() {
    mvc =
        MockMvcBuilders.standaloneSetup(new ProbeController())
            .setControllerAdvice(new ApiExceptionHandler(JsonMapper.builder().build()))
            .build();
  }

  @Test
  void validationUsesReasonAndJsonNamesIncludingNestedPaths() throws Exception {
    mvc.perform(
            post("/probe")
                .contentType("application/json")
                .content(
                    "{\"customer_name\":\"\",\"pickups\":[{\"requested_pickup_at\":\"\",\"store_ref\":\"\"}]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(
            jsonPath(
                "$.errors[*].field",
                containsInAnyOrder(
                    "customer_name", "pickups[0].requested_pickup_at", "pickups[0].store_ref")))
        .andExpect(jsonPath("$.errors[*].reason", everyItem(not(emptyOrNullString()))))
        .andExpect(jsonPath("$.errors[*].message").isEmpty());
  }

  @Test
  void unsupportedMethodHasEnvelopeAndAllowHeader() throws Exception {
    mvc.perform(get("/probe"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(header().string("Allow", containsString("POST")))
        .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
        .andExpect(jsonPath("$.message").isString())
        .andExpect(jsonPath("$.errors").isArray());
  }

  @Test
  void unsupportedMediaHasEnvelope() throws Exception {
    mvc.perform(post("/probe").contentType("text/plain").content("not-json"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
        .andExpect(jsonPath("$.message").isString())
        .andExpect(jsonPath("$.errors").isArray());
  }
}
