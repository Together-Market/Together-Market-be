package com.togethermarket.store.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.togethermarket.global.auth.OwnerAccess;
import com.togethermarket.store.entity.*;
import com.togethermarket.store.exception.OwnerStoreException;
import com.togethermarket.store.repository.*;
import com.togethermarket.store.service.OwnerStoreHoursService;
import com.togethermarket.user.entity.*;
import com.togethermarket.user.repository.UserRepository;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OwnerStoreHoursControllerTest {
  private final StoreRepository stores = mock(StoreRepository.class);
  private final StoreHourRepository hours = mock(StoreHourRepository.class);
  private final UserRepository users = mock(UserRepository.class);
  private final OwnerAccess access = mock(OwnerAccess.class);
  private MockMvc mvc;
  private static final String PATH = "/owner/stores/3/hours";
  private static final String ITEM =
      """
    {"weekday":6,"target_date":null,"hour_type":"BUSINESS","is_closed":false,
    "opens_at":"09:00:00","closes_at":"20:00:00","end_day_offset":0}
    """;

  @BeforeEach
  void setup() {
    when(access.requireOwner(any())).thenReturn(2L);
    when(users.findById(2L))
        .thenReturn(Optional.of(new User("owner@test.invalid", "hash", "사장", UserRole.OWNER)));
    Store store = new Store(2L, "꽃담", "꽃집", "용인시", null, null, "010");
    when(stores.findByIdAndOwnerId(3L, 2L)).thenReturn(Optional.of(store));
    when(stores.findOwnedForUpdate(3L, 2L)).thenReturn(Optional.of(store));
    when(hours.saveAllAndFlush(anyList())).thenAnswer(c -> c.getArgument(0));
    mvc =
        MockMvcBuilders.standaloneSetup(
                new OwnerStoreHoursController(
                    new OwnerStoreHoursService(stores, hours, users), access))
            .setControllerAdvice(new OwnerStoreExceptionHandler())
            .build();
  }

  private String body(String item) {
    return "{\"items\":[" + item + "]}";
  }

  @Test
  void getReturnsWeeklyAndDateExceptionsWithExactWireTypes() throws Exception {
    when(hours.findByStoreId(3L))
        .thenReturn(
            List.of(
                new StoreHour(
                    3L,
                    null,
                    LocalDate.of(2026, 11, 14),
                    HourType.PICKUP,
                    true,
                    null,
                    null,
                    (short) 0),
                new StoreHour(
                    3L,
                    (short) 6,
                    null,
                    HourType.BUSINESS,
                    false,
                    LocalTime.of(9, 0),
                    LocalTime.of(20, 0),
                    (short) 0)));
    mvc.perform(get(PATH))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.store_id").value("3"))
        .andExpect(jsonPath("$.data.items[0].weekday").value(6))
        .andExpect(jsonPath("$.data.items[0].opens_at").value("09:00:00"))
        .andExpect(jsonPath("$.data.items[0].id").doesNotExist())
        .andExpect(jsonPath("$.data.items[1].target_date").value("2026-11-14"))
        .andExpect(jsonPath("$.data.items[1].is_closed").value(true))
        .andExpect(jsonPath("$.data.items[1].opens_at").isEmpty());
  }

  @Test
  void emptyGetAndPutAreAllowed() throws Exception {
    when(hours.findByStoreId(3L)).thenReturn(List.of());
    mvc.perform(get(PATH)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
    mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(body("")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items").isEmpty());
    var order = inOrder(stores, hours);
    order.verify(stores).findOwnedForUpdate(3L, 2L);
    order.verify(hours).deleteForStore(3L);
    order.verify(hours).saveAllAndFlush(List.of());
  }

  @Test
  void replacementAndOvernightAccepted() throws Exception {
    mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(body(ITEM)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].hour_type").value("BUSINESS"));
    String night =
        ITEM.replace("09:00:00", "22:00:00")
            .replace("20:00:00", "02:00:00")
            .replace("\"end_day_offset\":0", "\"end_day_offset\":1");
    mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(body(night)))
        .andExpect(status().isOk());
    String fullDay =
        ITEM.replace("20:00:00", "09:00:00")
            .replace("\"end_day_offset\":0", "\"end_day_offset\":1");
    mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(body(fullDay)))
        .andExpect(status().isOk());
  }

  @Test
  void invalidRowsNeverDeleteExistingSchedule() throws Exception {
    List<String> bad =
        List.of(
            ITEM.replace("\"weekday\":6", "\"weekday\":0"),
            ITEM.replace("\"weekday\":6", "\"weekday\":6.5"),
            ITEM.replace("\"weekday\":6", "\"weekday\":\"6\""),
            ITEM.replace("\"weekday\":6", "\"weekday\":null"),
            ITEM.replace("\"target_date\":null", "\"target_date\":\"2026-11-14\""),
            ITEM.replace("\"weekday\":6", "\"weekday\":null")
                .replace("\"target_date\":null", "\"target_date\":\"2026-02-30\""),
            ITEM.replace("BUSINESS", "UNKNOWN"),
            ITEM.replace("\"is_closed\":false", "\"is_closed\":\"false\""),
            ITEM.replace("\"is_closed\":false", "\"is_closed\":true"),
            ITEM.replace("09:00:00", "24:00:00"),
            ITEM.replace("09:00:00", "09:00"),
            ITEM.replace("\"opens_at\":\"09:00:00\"", "\"opens_at\":null"),
            ITEM.replace("20:00:00", "09:00:00"),
            ITEM.replace("\"end_day_offset\":0", "\"end_day_offset\":1"),
            ITEM.replace("\"end_day_offset\":0", "\"end_day_offset\":2"),
            ITEM.replace("\"end_day_offset\":0", "\"end_day_offset\":null"),
            ITEM.replace("\"weekday\":6,", ""),
            ITEM.replace("\"weekday\":6", "\"weekday\":6,\"id\":1"));
    for (String row : bad)
      mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(body(row)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(body(ITEM + "," + ITEM)))
        .andExpect(status().isBadRequest());
    for (String malformed :
        List.of(
            "{}",
            "null",
            "[]",
            "{\"items\":null}",
            "{\"items\":[null]}",
            "{\"items\":[],\"owner_id\":2}"))
      mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(malformed))
          .andExpect(status().isBadRequest());
    verifyNoInteractions(hours);
  }

  @Test
  void deniedOwnerCannotReadOrReplace() throws Exception {
    when(stores.findByIdAndOwnerId(3L, 2L)).thenReturn(Optional.empty());
    when(stores.findOwnedForUpdate(3L, 2L)).thenReturn(Optional.empty());
    mvc.perform(get(PATH)).andExpect(status().isNotFound());
    mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(body(ITEM)))
        .andExpect(status().isNotFound());
    verifyNoInteractions(hours);
  }

  @Test
  void roleSessionAndPathValidation() throws Exception {
    mvc.perform(get("/owner/stores/0/hours")).andExpect(status().isBadRequest());
    mvc.perform(get("/owner/stores/99999999999999999999999/hours"))
        .andExpect(status().isBadRequest());
    when(users.findById(2L))
        .thenReturn(
            Optional.of(new User("customer@test.invalid", "hash", "고객", UserRole.CUSTOMER)));
    mvc.perform(get(PATH)).andExpect(status().isForbidden());
    when(access.requireOwner(any()))
        .thenThrow(new OwnerStoreException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다."));
    mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(body(ITEM)))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(hours);
  }
}
