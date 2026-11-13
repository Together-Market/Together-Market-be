package com.togethermarket.recommendation.repository;

import com.togethermarket.recommendation.entity.PurchaseRequest;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseRequestRepository extends JpaRepository<PurchaseRequest, Long> {
  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @org.springframework.data.jpa.repository.Query("select p from PurchaseRequest p where p.id = :id")
  java.util.Optional<PurchaseRequest> findLockedById(
      @org.springframework.data.repository.query.Param("id") Long id);

  @org.springframework.data.jpa.repository.Query(
      """
      select p.id from PurchaseRequest p where p.status = :status and
      ((p.recommendationDeadlineAt is not null and p.recommendationDeadlineAt <= :now) or
       (p.recommendationDeadlineAt is null and p.createdAt <= :cutoff))
      order by p.createdAt, p.id
      """)
  java.util.List<Long> findExpiredIds(
      @org.springframework.data.repository.query.Param("status")
          com.togethermarket.recommendation.entity.RecommendationStatus status,
      @org.springframework.data.repository.query.Param("now") java.time.LocalDateTime now,
      @org.springframework.data.repository.query.Param("cutoff") java.time.LocalDateTime cutoff,
      org.springframework.data.domain.Pageable pageable);
}
