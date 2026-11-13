package com.togethermarket.reservation.service;

import com.togethermarket.global.api.ApiException;
import com.togethermarket.product.entity.Product;
import com.togethermarket.product.repository.ProductRepository;
import com.togethermarket.product.service.ProductAvailabilityService;
import com.togethermarket.recommendation.entity.RecommendationStatus;
import com.togethermarket.recommendation.repository.*;
import com.togethermarket.reservation.dto.request.CreateReservationRequest;
import com.togethermarket.reservation.dto.response.ReservationResponse;
import com.togethermarket.reservation.entity.*;
import com.togethermarket.reservation.repository.*;
import com.togethermarket.store.entity.Store;
import com.togethermarket.store.repository.StoreRepository;
import com.togethermarket.user.entity.UserRole;
import com.togethermarket.user.repository.UserRepository;
import jakarta.persistence.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ReservationService {
  private static final ZoneId KST = ZoneId.of("Asia/Seoul");
  private final UserRepository users;
  private final RecommendationCandidateRepository candidates;
  private final PurchaseRequestRepository requests;
  private final RecommendationItemRepository items;
  private final ProductRepository products;
  private final StoreRepository stores;
  private final ReservationRepository reservations;
  private final StoreOrderRepository orders;
  private final ProductAvailabilityService availability;
  private final ReservationPolicy policy;
  private final Clock clock;
  private final EntityManager entityManager;

  public ReservationService(
      UserRepository users,
      RecommendationCandidateRepository candidates,
      PurchaseRequestRepository requests,
      RecommendationItemRepository items,
      ProductRepository products,
      StoreRepository stores,
      ReservationRepository reservations,
      StoreOrderRepository orders,
      ProductAvailabilityService availability,
      ReservationPolicy policy,
      Clock clock,
      EntityManager entityManager) {
    this.users = users;
    this.candidates = candidates;
    this.requests = requests;
    this.items = items;
    this.products = products;
    this.stores = stores;
    this.reservations = reservations;
    this.orders = orders;
    this.availability = availability;
    this.policy = policy;
    this.clock = clock;
    this.entityManager = entityManager;
  }

  public record Created(ReservationResponse reservation, boolean replay) {}

  public Created create(Long customer, String key, CreateReservationRequest input) {
    if (key == null
        || !key.matches(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
      throw invalid("Idempotency-Key는 UUID 형식이어야 합니다.");
    // Locking an existing customer row also serializes first use of a key (no gap-lock race).
    var user =
        users
            .findForReservation(customer)
            .orElseThrow(
                () ->
                    new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "사용자를 확인할 수 없습니다."));
    if (user.getRole() != UserRole.CUSTOMER)
      throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "고객 권한이 필요합니다.");
    var pickupMap = new TreeMap<Long, Instant>();
    for (var p : input.pickups()) {
      if (local(p.requestedPickupAt().toInstant()).getYear() < 1000
          || local(p.requestedPickupAt().toInstant()).getYear() > 9999)
        throw invalid("수령시간이 DB 저장 가능 범위를 벗어났습니다.");
      if (p.requestedPickupAt().getNano() != 0) throw invalid("수령시간은 초 단위로 입력해 주세요.");
      if (pickupMap.put(id(p.storeId()), p.requestedPickupAt().toInstant()) != null)
        throw invalid("서로 다른 두 점포를 지정해 주세요.");
    }
    if (pickupMap.size() != 2) throw invalid("수령 점포는 정확히 두 곳이어야 합니다.");
    Long candidateId = id(input.recommendationCandidateId());
    String hash = hash(input, candidateId, pickupMap);
    var existing = reservations.findByCustomerIdAndRequestKey(customer, key);
    if (existing.isPresent()) {
      var saved = existing.get();
      if (!saved.getRequestPayloadHash().equals(hash))
        throw conflict("IDEMPOTENCY_KEY_REUSED", "같은 키에 다른 예약 내용을 사용할 수 없습니다.");
      expire(saved, local(clock.instant()));
      return new Created(response(saved), true);
    }
    var candidate = candidates.findById(candidateId).orElseThrow(this::notFound);
    var purchase =
        requests
            .findById(candidate.getPurchaseRequestId())
            .filter(p -> p.getCustomerId().equals(customer))
            .orElseThrow(this::notFound);
    if (purchase.getStatus() != RecommendationStatus.READY)
      throw conflict("RECOMMENDATION_NOT_READY", "추천이 완료되지 않았습니다.");
    var recommended = items.findByRecommendationCandidateIdOrderByProductIdAsc(candidateId);
    if (recommended.size() != 2
        || recommended.stream().anyMatch(i -> i.getQuantity() != 1)
        || recommended.get(0).getProductId().equals(recommended.get(1).getProductId()))
      throw conflict("INVALID_RECOMMENDATION", "서로 다른 두 점포의 상품이 필요합니다.");
    var selected =
        recommended.stream()
            .map(i -> products.findById(i.getProductId()).orElseThrow(this::notFound))
            .sorted(Comparator.comparing(Product::getId))
            .toList();
    var storeIds =
        selected.stream()
            .map(Product::getStoreId)
            .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
    if (storeIds.size() != 2 || !storeIds.equals(pickupMap.keySet()))
      throw invalid("추천 구성과 수령 점포가 일치해야 합니다.");
    var lockedStores = new HashMap<Long, Store>();
    for (var store : storeIds)
      lockedStores.put(store, stores.findForReservation(store).orElseThrow(this::notFound));
    // Initial product reads only identify immutable store IDs. Refresh after locking to avoid stale
    // price/status snapshots.
    for (var product : selected) entityManager.refresh(product, LockModeType.PESSIMISTIC_WRITE);
    Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
    for (var product : selected) {
      var result = availability.check(product.getId(), pickupMap.get(product.getStoreId()), now);
      if (!result.available())
        throw conflict("CANDIDATE_UNAVAILABLE", "상품 수령 조건을 충족하지 못합니다: " + result.reasons());
    }
    BigDecimal total =
        selected.stream().map(Product::getPriceAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    if (total.compareTo(input.expectedTotalAmount()) != 0)
      throw conflict("PRICE_CHANGED", "현재 상품 합계 금액을 확인해 주세요.");
    if (total.compareTo(purchase.getBudgetAmount()) > 0)
      throw conflict("BUDGET_EXCEEDED", "현재 합계 금액이 구매 예산을 초과합니다.");
    Instant latestStart =
        selected.stream()
            .map(
                p ->
                    pickupMap
                        .get(p.getStoreId())
                        .minus(Duration.ofMinutes(p.getPreparationMinutes())))
            .min(Comparator.naturalOrder())
            .orElseThrow();
    var reservation =
        reservations.saveAndFlush(
            new Reservation(
                customer,
                candidateId,
                ReservationStatus.PENDING,
                null,
                input.customerName(),
                input.customerPhone(),
                input.customerNote(),
                local(policy.deadline(now, latestStart)),
                key,
                hash,
                local(now)));
    for (var product : selected)
      orders.save(
          new StoreOrder(
              reservation.getId(),
              product.getStoreId(),
              product.getId(),
              1,
              product.getPriceAmount(),
              product.getName(),
              lockedStores.get(product.getStoreId()).getAddress(),
              local(pickupMap.get(product.getStoreId())),
              ApprovalStatus.PENDING,
              null,
              null));
    orders.flush();
    return new Created(response(reservation), false);
  }

  public ReservationResponse detail(Long customer, Long id) {
    var saved = reservations.findByIdAndCustomerId(id, customer).orElseThrow(this::notFound);
    expire(saved, local(clock.instant()));
    return response(saved);
  }

  public ReservationResponse.Page list(
      Long customer, int page, int size, ReservationStatus status) {
    if (page < 1 || size < 1 || size > 100 || (long) (page - 1) * size > Integer.MAX_VALUE)
      throw invalid("page는 1 이상, size는 1~100으로 입력해 주세요.");
    var now = local(clock.instant());
    for (var r : reservations.findExpiredForUpdate(customer, now)) expire(r, now);
    reservations.flush();
    var pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
    var result =
        status == null
            ? reservations.findByCustomerId(customer, pageable)
            : reservations.findByCustomerIdAndStatus(customer, status, pageable);
    return new ReservationResponse.Page(
        result.getContent().stream().map(this::response).toList(),
        page,
        size,
        result.getTotalElements());
  }

  private void expire(Reservation r, LocalDateTime now) {
    if (r.expireIfDue(now))
      for (var o : orders.findByReservationIdOrderByIdAsc(r.getId())) o.expireIfPending();
  }

  private ReservationResponse response(Reservation r) {
    var orderResponses =
        orders.findByReservationIdOrderByIdAsc(r.getId()).stream()
            .map(
                o ->
                    new ReservationResponse.Order(
                        o.getId().toString(),
                        r.getId().toString(),
                        o.getStoreId().toString(),
                        stores.findById(o.getStoreId()).map(Store::getName).orElse(null),
                        o.getProductId().toString(),
                        o.getQuantity(),
                        o.getUnitPriceSnapshot(),
                        o.getProductNameSnapshot(),
                        o.getPickupAddressSnapshot(),
                        offset(o.getRequestedPickupAt()),
                        o.getApprovalStatus().name(),
                        offset(o.getRespondedAt()),
                        o.getRejectionReason()))
            .toList();
    var total =
        orderResponses.stream()
            .map(o -> o.unitPriceSnapshot().multiply(BigDecimal.valueOf(o.quantity())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    return new ReservationResponse(
        r.getId().toString(),
        r.getCustomerId().toString(),
        r.getRecommendationCandidateId().toString(),
        r.getStatus().name(),
        r.getFailureCode(),
        r.getCustomerName(),
        r.getCustomerPhone(),
        r.getCustomerNote(),
        offset(r.getApprovalDeadlineAt()),
        offset(r.getCreatedAt()),
        total,
        orderResponses);
  }

  static Long id(String text) {
    try {
      long n = Long.parseLong(text);
      if (n <= 0) throw invalid("ID는 양의 정수 문자열이어야 합니다.");
      return n;
    } catch (NumberFormatException e) {
      throw invalid("ID가 허용 범위를 초과했습니다.");
    }
  }

  private static String hash(
      CreateReservationRequest input, Long candidate, SortedMap<Long, Instant> pickups) {
    try {
      var bytes = new ByteArrayOutputStream();
      var out = new DataOutputStream(bytes);
      out.writeLong(candidate);
      for (String s :
          Arrays.asList(
              input.customerName(),
              input.customerPhone(),
              input.customerNote(),
              input.expectedTotalAmount().stripTrailingZeros().toPlainString())) {
        if (s == null) {
          out.writeInt(-1);
        } else {
          byte[] b = s.getBytes(StandardCharsets.UTF_8);
          out.writeInt(b.length);
          out.write(b);
        }
      }
      for (var p : pickups.entrySet()) {
        out.writeLong(p.getKey());
        out.writeLong(p.getValue().getEpochSecond());
      }
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    } catch (IOException | NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static LocalDateTime local(Instant t) {
    return LocalDateTime.ofInstant(t, KST);
  }

  private static OffsetDateTime offset(LocalDateTime t) {
    return t == null ? null : t.atZone(KST).toOffsetDateTime();
  }

  private ApiException notFound() {
    return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "예약 또는 추천 정보를 찾을 수 없습니다.");
  }

  private static ApiException invalid(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
  }

  private static ApiException conflict(String code, String message) {
    return new ApiException(HttpStatus.CONFLICT, code, message);
  }
}
