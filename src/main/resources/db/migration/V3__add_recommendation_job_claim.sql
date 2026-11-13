-- BE3 durable recommendation ownership. V1/V2 remain unchanged.
ALTER TABLE purchase_requests
  ADD COLUMN recommendation_claim_token VARCHAR(36) NULL COMMENT '비동기 추천 선점 토큰',
  ADD COLUMN recommendation_deadline_at DATETIME NULL COMMENT '비동기 추천 제한시각 KST',
  ADD CONSTRAINT ck_purchase_requests_job_pair CHECK (
    (recommendation_claim_token IS NULL AND recommendation_deadline_at IS NULL) OR
    (recommendation_claim_token IS NOT NULL AND recommendation_deadline_at IS NOT NULL)),
  ADD INDEX ix_purchase_requests_expiry (status, recommendation_deadline_at, created_at);
