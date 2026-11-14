package com.togethermarket.product.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.togethermarket.global.auth.OwnerAccess;
import com.togethermarket.product.entity.*;
import com.togethermarket.product.repository.*;
import com.togethermarket.product.service.OwnerProductService;
import com.togethermarket.recommendation.entity.PurposeCategory;
import com.togethermarket.recommendation.repository.PurposeCategoryRepository;
import com.togethermarket.store.controller.OwnerStoreExceptionHandler;
import com.togethermarket.store.entity.Store;
import com.togethermarket.store.exception.OwnerStoreException;
import com.togethermarket.store.repository.StoreRepository;
import com.togethermarket.user.entity.*;
import com.togethermarket.user.repository.UserRepository;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OwnerProductControllerTest {
  private final ProductRepository products = mock(ProductRepository.class);
  private final ProductPurposeTagRepository tags = mock(ProductPurposeTagRepository.class);
  private final StoreRepository stores = mock(StoreRepository.class);
  private final UserRepository users = mock(UserRepository.class);
  private final PurposeCategoryRepository categories = mock(PurposeCategoryRepository.class);
  private final OwnerAccess access = mock(OwnerAccess.class);
  private MockMvc mvc;
  private Product product;
  private static final String PATH = "/owner/stores/3/products";
  private static final String CREATE =
      """
    {"name":"꽃다발","description":"생일 선물","image_url":"https://example.com/flower.jpg",
     "price_amount":30000,"preparation_minutes":60,"sale_status":"ON_SALE","purpose_category_ids":["11"]}
    """;

  @BeforeEach
  void setup() {
    when(access.requireOwner(any())).thenReturn(2L);
    when(users.findById(2L))
        .thenReturn(Optional.of(new User("owner@test.invalid", "hash", "사장", UserRole.OWNER)));
    when(stores.findByIdAndOwnerId(3L, 2L))
        .thenReturn(Optional.of(new Store(2L, "가게", "꽃집", "용인", null, null, "010")));
    product =
        new Product(
            3L,
            "꽃다발",
            "생일 선물",
            "https://example.com/flower.jpg",
            BigDecimal.valueOf(30000),
            60,
            SaleStatus.ON_SALE);
    ReflectionTestUtils.setField(product, "id", 10L);
    when(products.findForUpdate(10L, 3L)).thenReturn(Optional.of(product));
    when(products.saveAndFlush(any(Product.class)))
        .thenAnswer(
            c -> {
              Product p = c.getArgument(0);
              ReflectionTestUtils.setField(p, "id", 10L);
              return p;
            });
    when(categories.findAllById(anyList())).thenReturn(List.of(new PurposeCategory(1L, "생일")));
    when(tags.findByIdProductId(10L)).thenReturn(List.of(new ProductPurposeTag(10L, 11L)));
    mvc =
        MockMvcBuilders.standaloneSetup(
                new OwnerProductController(
                    new OwnerProductService(products, tags, stores, users, categories), access))
            .setControllerAdvice(new OwnerStoreExceptionHandler())
            .build();
  }

  @Test
  void createReturns201SnakeCaseStringIdsAndSavesTags() throws Exception {
    mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(CREATE))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.id").value("10"))
        .andExpect(jsonPath("$.data.store_id").value("3"))
        .andExpect(jsonPath("$.data.price_amount").value(30000))
        .andExpect(jsonPath("$.data.purpose_category_ids[0]").value("11"))
        .andExpect(jsonPath("$.data.owner_id").doesNotExist());
    verify(tags)
        .saveAllAndFlush(
            argThat(
                list -> {
                  var t = list.iterator().next();
                  return t.getId().getProductId().equals(10L)
                      && t.getId().getPurposeCategoryId().equals(11L);
                }));
  }

  @Test
  void listUsesBatchTagsAndOptionalFilter() throws Exception {
    when(products.findByStoreId(eq(3L), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(product)));
    when(products.findByStoreIdAndSaleStatus(eq(3L), eq(SaleStatus.ON_SALE), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(product)));
    when(tags.findByIdProductIdIn(List.of(10L)))
        .thenReturn(List.of(new ProductPurposeTag(10L, 11L)));
    mvc.perform(get(PATH))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.page").value(1))
        .andExpect(jsonPath("$.data.size").value(20))
        .andExpect(jsonPath("$.data.total_count").value(1))
        .andExpect(jsonPath("$.data.items[0].purpose_category_ids[0]").value("11"));
    mvc.perform(get(PATH + "?sale_status=ON_SALE")).andExpect(status().isOk());
    verify(products)
        .findByStoreId(
            eq(3L),
            argThat(p -> p.getPageNumber() == 0 && p.getSort().getOrderFor("id").isDescending()));
    verify(tags, never()).findByIdProductId(anyLong());
  }

  @Test
  void patchOmissionAndNullImagePreserveOtherDataAndTags() throws Exception {
    mvc.perform(
            patch(PATH + "/10")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"image_url\":null,\"price_amount\":32000}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("꽃다발"))
        .andExpect(jsonPath("$.data.image_url").isEmpty())
        .andExpect(jsonPath("$.data.price_amount").value(32000));
    assertEquals(SaleStatus.ON_SALE, product.getSaleStatus());
    verify(tags, never()).deleteForProduct(anyLong());
  }

  @Test
  void patchReplacesTagsAndStatusUsesDedicatedEndpoint() throws Exception {
    mvc.perform(
            patch(PATH + "/10")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"purpose_category_ids\":[\"12\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.purpose_category_ids[0]").value("12"));
    verify(tags).deleteForProduct(10L);
    for (String s : List.of("SOLD_OUT", "PAUSED", "ON_SALE"))
      mvc.perform(
              patch(PATH + "/10/sale-status")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"sale_status\":\"" + s + "\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.sale_status").value(s));
  }

  @Test
  void invalidInputCannotWrite() throws Exception {
    for (String bad :
        List.of(
            "{}",
            "null",
            "[]",
            "{",
            "{\"name\":null}",
            "{\"description\":\" \"}",
            "{\"store_id\":\"4\"}",
            "{\"sale_status\":\"PAUSED\"}",
            "{\"image_url\":\"http://example.com/x\"}",
            "{\"price_amount\":-1}",
            "{\"price_amount\":1.5}",
            "{\"price_amount\":1000000000000}",
            "{\"price_amount\":\"1\"}",
            "{\"preparation_minutes\":2147483648}",
            "{\"preparation_minutes\":-1}",
            "{\"purpose_category_ids\":[]}",
            "{\"purpose_category_ids\":[11]}",
            "{\"purpose_category_ids\":[\"11\",\"11\"]}")) {
      mvc.perform(patch(PATH + "/10").contentType(MediaType.APPLICATION_JSON).content(bad))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
    mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"flower\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            patch(PATH + "/10/sale-status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sale_status\":\"INVALID\"}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(products, tags);
  }

  @Test
  void tagsMustExistAndBeMiddleCategories() throws Exception {
    when(categories.findAllById(anyList())).thenReturn(List.of());
    mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(CREATE))
        .andExpect(status().isBadRequest());
    when(categories.findAllById(anyList())).thenReturn(List.of(new PurposeCategory(null, "대분류")));
    mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(CREATE))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(products, tags);
  }

  @Test
  void foreignStoreAndProductAre404AndDoNotWrite() throws Exception {
    when(stores.findByIdAndOwnerId(3L, 2L)).thenReturn(Optional.empty());
    mvc.perform(get(PATH)).andExpect(status().isNotFound());
    mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(CREATE))
        .andExpect(status().isNotFound());
    verifyNoInteractions(products, tags);
    when(stores.findByIdAndOwnerId(3L, 2L))
        .thenReturn(Optional.of(new Store(2L, "s", "b", "a", null, null, "p")));
    mvc.perform(
            patch(PATH + "/99")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"new\"}"))
        .andExpect(status().isNotFound());
    mvc.perform(
            patch(PATH + "/99/sale-status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sale_status\":\"PAUSED\"}"))
        .andExpect(status().isNotFound());
    verifyNoInteractions(tags);
  }

  @Test
  void roleSessionAndQueryValidation() throws Exception {
    for (String query :
        List.of("page=0", "size=101", "page=x", "page=2147483647&size=100", "sale_status=INVALID"))
      mvc.perform(get(PATH + "?" + query)).andExpect(status().isBadRequest());
    mvc.perform(get("/owner/stores/0/products")).andExpect(status().isBadRequest());
    when(users.findById(2L))
        .thenReturn(Optional.of(new User("c@test.invalid", "hash", "고객", UserRole.CUSTOMER)));
    mvc.perform(get(PATH)).andExpect(status().isForbidden());
    when(access.requireOwner(any()))
        .thenThrow(new OwnerStoreException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다."));
    mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(CREATE))
        .andExpect(status().isUnauthorized());
  }
}
