package com.togethermarket.recommendation.repository;

import com.togethermarket.recommendation.entity.PurchaseRequest;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseRequestRepository extends JpaRepository<PurchaseRequest, Long> {}
