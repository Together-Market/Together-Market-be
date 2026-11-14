package com.togethermarket.store.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.togethermarket.global.auth.OwnerAccess;
import com.togethermarket.store.entity.Store;
import com.togethermarket.store.exception.OwnerStoreException;
import com.togethermarket.store.repository.StoreRepository;
import com.togethermarket.store.service.OwnerStoreService;
import com.togethermarket.store.service.geocoding.AddressGeocoder;
import com.togethermarket.store.service.geocoding.Coordinates;
import com.togethermarket.user.entity.User;
import com.togethermarket.user.entity.UserRole;
import com.togethermarket.user.repository.UserRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OwnerStoreControllerTest {
  private final StoreRepository stores = mock(StoreRepository.class);
  private final UserRepository users = mock(UserRepository.class);
  private final OwnerAccess access = mock(OwnerAccess.class);
  private final AddressGeocoder geocoder = mock(AddressGeocoder.class);
  private MockMvc mvc;
  private static final String CREATE =
      """
      {"name":"꽃담","business_type":"꽃집","address":"용인시",
       "latitude":37.322,"longitude":127.095,"phone":"031-000-0000"}
      """;

  private Store store() {
    var s =
        new Store(
            2L,
            "꽃담",
            "꽃집",
            "용인시",
            new BigDecimal("37.322"),
            new BigDecimal("127.095"),
            "031-000-0000");
    ReflectionTestUtils.setField(s, "id", 3L);
    return s;
  }

  @BeforeEach
  void setup() {
    when(access.requireOwner(any())).thenReturn(2L);
    when(users.findById(2L))
        .thenReturn(Optional.of(new User("owner@test.invalid", "hash", "사장님", UserRole.OWNER)));
    mvc =
        MockMvcBuilders.standaloneSetup(
                new OwnerStoreController(new OwnerStoreService(stores, users), access, geocoder))
            .setControllerAdvice(new OwnerStoreExceptionHandler())
            .build();
  }

  @Test
  void listUsesOwnerAndOneBasedPagination() throws Exception {
    when(stores.findByOwnerId(eq(2L), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(store())));
    mvc.perform(get("/owner/stores"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].id").value("3"))
        .andExpect(jsonPath("$.data.items[0].business_type").value("꽃집"))
        .andExpect(jsonPath("$.data.items[0].owner_id").doesNotExist())
        .andExpect(jsonPath("$.data.page").value(1))
        .andExpect(jsonPath("$.data.size").value(20))
        .andExpect(jsonPath("$.data.total_count").value(1));
    verify(stores)
        .findByOwnerId(
            eq(2L),
            argThat(
                p ->
                    p.getPageNumber() == 0
                        && p.getPageSize() == 20
                        && p.getSort().getOrderFor("id").isDescending()));
  }

  @Test
  void createUsesAuthenticatedOwnerAndReturns201() throws Exception {
    when(stores.save(any(Store.class)))
        .thenAnswer(
            call -> {
              Store s = call.getArgument(0);
              ReflectionTestUtils.setField(s, "id", 3L);
              return s;
            });
    mvc.perform(post("/owner/stores").contentType(MediaType.APPLICATION_JSON).content(CREATE))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.id").value("3"))
        .andExpect(jsonPath("$.data.owner_id").value("2"));
    verify(stores).save(argThat(s -> s.getOwnerId().equals(2L) && s.getName().equals("꽃담")));
  }

  @Test
  void patchKeepsOmittedFieldsAndRejectsClearingCoordinates() throws Exception {
    Store s = store();
    when(stores.findOwnedForUpdate(3L, 2L)).thenReturn(Optional.of(s));
    mvc.perform(
            patch("/owner/stores/3")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"031-111-1111\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.phone").value("031-111-1111"))
        .andExpect(jsonPath("$.data.name").value("꽃담"))
        .andExpect(jsonPath("$.data.owner_id").doesNotExist());
    mvc.perform(
            patch("/owner/stores/3")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"latitude\":null,\"longitude\":null}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("GEOCODE_STORAGE_NOT_ALLOWED"));
    assertEquals(new BigDecimal("37.322"), s.getLatitude());
    assertEquals(new BigDecimal("127.095"), s.getLongitude());
    assertEquals("031-111-1111", s.getPhone());
  }

  @Test
  void livePreviewUsesOwnerAuthenticationAndCannotBeStored() throws Exception {
    when(geocoder.lookup("경기도 용인시 도로 1"))
        .thenReturn(new Coordinates(new BigDecimal("37.2758538"), new BigDecimal("127.1512813")));
    mvc.perform(get("/owner/stores/geocode").param("address", "  경기도 용인시 도로 1  "))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.data.address").value("경기도 용인시 도로 1"))
        .andExpect(jsonPath("$.data.latitude").value(37.2758538))
        .andExpect(jsonPath("$.data.longitude").value(127.1512813))
        .andExpect(jsonPath("$.data.coordinate_source").value("KAKAO"))
        .andExpect(jsonPath("$.data.storage_allowed").value(false));
    mvc.perform(
            post("/owner/stores")
                .contentType(MediaType.APPLICATION_JSON)
                .content(CREATE.replace("\"name\":", "\"coordinate_source\":\"KAKAO\",\"name\":")))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("GEOCODE_STORAGE_NOT_ALLOWED"));
    verify(stores, never()).save(any());
  }

  @Test
  void previewValidationAndProviderErrorsKeepCommonEnvelope() throws Exception {
    for (String address : List.of("", " ", "a".repeat(501), "도로\n주소"))
      mvc.perform(get("/owner/stores/geocode").param("address", address))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    mvc.perform(get("/owner/stores/geocode")).andExpect(status().isBadRequest());
    verifyNoInteractions(geocoder);
    when(geocoder.lookup("주소"))
        .thenThrow(
            new OwnerStoreException(
                HttpStatus.UNPROCESSABLE_ENTITY, "ADDRESS_AMBIGUOUS", "주소를 구체화해 주세요."));
    mvc.perform(get("/owner/stores/geocode").param("address", "주소"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("ADDRESS_AMBIGUOUS"))
        .andExpect(jsonPath("$.errors").isArray());
  }

  @Test
  void changedAddressRequiresCompleteFreshPairAndFailureDoesNotMutateAnything() throws Exception {
    var s = store();
    when(stores.findOwnedForUpdate(3L, 2L)).thenReturn(Optional.of(s));
    mvc.perform(
            patch("/owner/stores/3")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"address\":\"다른 주소\",\"phone\":\"new\"}"))
        .andExpect(status().isServiceUnavailable());
    assertEquals("용인시", s.getAddress());
    assertEquals("031-000-0000", s.getPhone());
    mvc.perform(
            patch("/owner/stores/3")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"address\":\"다른 주소\",\"latitude\":37.1}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            patch("/owner/stores/3")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"address\":\"다른 주소\",\"latitude\":37.1,\"longitude\":127.2}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.address").value("다른 주소"))
        .andExpect(jsonPath("$.data.latitude").value(37.1));
    verifyNoInteractions(geocoder);
  }

  @Test
  void missingCoordinatesNeverCreatePlaceholderOrCallKakaoForStorage() throws Exception {
    for (String pair :
        List.of(
            "", ",\"latitude\":null,\"longitude\":null", ",\"latitude\":37.1,\"longitude\":null"))
      mvc.perform(
              post("/owner/stores")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"name\":\"점포\",\"business_type\":\"카페\",\"address\":\"주소\",\"phone\":\"번호\""
                          + pair
                          + "}"))
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.code").value("GEOCODE_STORAGE_NOT_ALLOWED"));
    verify(stores, never()).save(any());
    verifyNoInteractions(geocoder);
  }

  @Test
  void foreignOrUnknownStoreReturns404() throws Exception {
    when(stores.findOwnedForUpdate(4L, 2L)).thenReturn(Optional.empty());
    mvc.perform(
            patch("/owner/stores/4")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"x\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void invalidPatchCannotMutateStore() throws Exception {
    Store s = store();
    when(stores.findOwnedForUpdate(3L, 2L)).thenReturn(Optional.of(s));
    mvc.perform(
            patch("/owner/stores/3")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"new\",\"latitude\":null}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("latitude"));
    assertEquals("031-000-0000", s.getPhone());
  }

  @Test
  void forbiddenRoleAndUnauthenticatedAccessAreRejected() throws Exception {
    when(users.findById(2L))
        .thenReturn(
            Optional.of(new User("customer@test.invalid", "hash", "고객", UserRole.CUSTOMER)));
    mvc.perform(get("/owner/stores")).andExpect(status().isForbidden());
    when(access.requireOwner(any()))
        .thenThrow(new OwnerStoreException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다."));
    mvc.perform(post("/owner/stores").contentType(MediaType.APPLICATION_JSON).content(CREATE))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(stores);
  }

  @Test
  void rejectsUnknownEmptyNullAndMalformedInputs() throws Exception {
    for (String body :
        List.of(
            "{}",
            "{\"name\":null}",
            "{\"owner_id\":\"9\"}",
            "{\"phone\":\" \"}",
            "{\"latitude\":\"37\"}",
            "{\"latitude\":91}",
            "{\"latitude\":37.12345678}",
            "null",
            "[1]",
            "{")) {
      mvc.perform(patch("/owner/stores/3").contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
    mvc.perform(
            post("/owner/stores")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"꽃담\"}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(stores);
  }

  @Test
  void rejectsInvalidPaginationAndIds() throws Exception {
    for (String query :
        List.of("page=0", "page=abc", "size=0", "size=101", "page=2147483647&size=100"))
      mvc.perform(get("/owner/stores?" + query)).andExpect(status().isBadRequest());
    for (String id : List.of("abc", "0", "-1", "999999999999999999999999"))
      mvc.perform(
              patch("/owner/stores/" + id)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"phone\":\"x\"}"))
          .andExpect(status().isBadRequest());
    verifyNoInteractions(stores);
  }
}
