# BE3 추천 후보 생성·재검증·조회 준비

후속 DB 연결·KST 변환·V3 저장소와 최신 활성화 설정은 [공통 코드 연결](be3-database-integration.md)을 참고하세요. 아래는 초기 내부 로직의 설계와 연결 경계 기록입니다.

공통 DB 연결과 분리해 검증한 내부 서비스 로직이다. HTTP API, DB 저장, 세션 인증과 연결된 상태는 아니다. 기존 구매조건 구현은 [be3-purchase-preparation.md](be3-purchase-preparation.md)를 참고한다.

## 구현 범위

| 클래스 | 처리 내용 |
| --- | --- |
| CandidateGenerator | 서버 상품 스냅샷과 공통 검증 결과로 서로 다른 두 점포의 예산 내 조합 생성, 중복 제거, AI 입력 후보 제한 |
| CandidateRevalidator | 현재 가격·수량으로 합계 계산, 공통 판매/시간 검증, 예산·서로 다른 점포·지난 수령시각 검사 |
| RecommendationReadService | 구매요청 소유권 확인, 추천 목록·상세, 실패 예약의 기존 대체 후보, 정렬·페이지 처리 |
| CandidateData | 공유 Entity와 분리한 내부 읽기 스냅샷. API 응답 DTO로 직접 직렬화하지 않음 |
| CatalogSource / RecommendationReadSource | 공유 Repository를 연결할 BE3 읽기 어댑터 경계 |
| ProductEligibility | BE2 공통 검증의 정확한 타입이 오면 감쌀 BE3 전용 어댑터. 팀 공통 인터페이스를 재정의하지 않음 |

서비스는 자동 Bean 등록하지 않는다. CUSTOMER 권한은 BE1 계층에서 검사하고, 인증된 세션 고객 ID만 서비스에 넘긴다. 생성기의 상품 ID·분류 태그·선호 점수 역시 서버에서 읽거나 계산해야 하며 클라이언트가 주장한 판매 가능 여부나 가격을 신뢰하지 않는다.

## 후보 생성과 기존 AI 모듈 연결

1. 서버가 조회한 상품 ID 집합을 받고 카탈로그를 한 번에 읽는다. 설정된 상품 상한을 넘으면 임의로 자르지 않고 호출자에게 오류를 반환한다. 어댑터는 가격·점포·메타데이터를 일관된 스냅샷으로 제공해야 한다.
2. BE2 검증 어댑터를 상품마다 한 번 호출한다. 모든 호출에 동일한 수령시각·평가 기준시각을 전달한다. 판매·영업·휴무·준비시간 규칙을 BE3에서 복제하지 않는다.
3. 서로 다른 두 점포, 상품별 1개, 총액이 예산 이하인 모든 조합을 만든다. 같은 업종이라는 이유만으로 제외하지 않는다. 수량 1은 현재 추천 모델의 제안이며 공개 계약으로 확정한 것은 아니다.
4. 사전 점수는 `50 × 평균 목적 태그 일치도 + 30 × 평균 서버 선호 점수 + 20 × 근접도`를 사용한다. 목적 태그는 정확한 중분류 1, 형제 분류 0.5, 나머지 0이다. 선호 점수가 없으면 0, 거리를 알 수 없으면 근접도 0이다. 이 규칙은 학습 모델이 아니라 AI 입력을 줄이기 위한 초기 휴리스틱이다.
5. `scoreFirstCount`개는 사전 점수 순으로 고른다. 이후에는 아직 선택하지 않은 점포 쌍·업종 쌍을 우선하고 동점이면 사전 점수와 숫자 상품 ID 순으로 정한다. `Limits`는 호출자가 명시하며 자동 기본값은 없다. 테스트 예시는 상품 200개·후보 12개·점수 우선 8개·거리 기준 1500m다. 후보 상한은 기존 HybridRecommendationService가 허용하는 12개 이하다.
6. 반환된 `RecommendationInput.Candidate`를 기존 HybridRecommendationService에 전달한다. AI 내용 평가·제외 조건 판단과 학습 가중치 순위화는 그 모듈에서 수행한다. 생성기의 `p_상품ID_상품ID`는 AI 응답을 대조할 임시 키이며 DB나 공개 API의 후보 ID가 아니다.

상품 태그와 텍스트 선호 답변을 서버 점수로 변환하는 데이터/어댑터는 아직 필요하다. 없는 태그나 설명을 생성해 채우지 않는다. 설명 null은 AI 입력에서 빈 문자열로만 변환한다.

```java
var generator = new CandidateGenerator(catalogSource, be2EligibilityAdapter, approvedLimits);
var inputs = generator.generate(purchaseSnapshot, serverProductIds, serverPreferences, clock.instant());
var ranked = hybridRecommendationService.recommend(context, inputs);
// 후속 DB 연결 시: 실제 Long 후보 ID 발급, 당시 가격·추천 이유·순위 저장,
// 후보 저장과 구매요청 READY 전이를 한 트랜잭션으로 처리한다.
```

## 현재 조건 재검증

- 추천 당시 단가는 SavedItem에 유지하고 현재 상품 단가와 분리한다. 가격이 달라져도 현재 합계가 예산 이하면 그 이유만으로 UNAVAILABLE이 되지 않는다.
- BE2의 불가 사유 코드는 그대로 전달한다. BE3 자체 내부 사유는 BUDGET_EXCEEDED, DISTINCT_STORES_REQUIRED, PICKUP_TIME_PASSED다. 공개 JSON 사유 필드/코드는 팀 계약에 맞춰 별도 매핑해야 한다.
- 합계는 현재 단가 × 저장된 수량으로 계산하며 정수 오버플로는 실패 처리한다. 수령시각이 평가시각과 같은 경계도 지난 시각으로 본다.
- 여러 후보가 같은 상품을 포함해도 카탈로그는 한 번에 읽고 공통 검증은 상품당 한 번 수행한다. 가격 조회와 검증 사이 경쟁을 완전히 방지하는 DB 트랜잭션 구현은 아직 없다. 조회 결과는 예약 보장이나 재고 확보가 아니며 BE1이 예약 직전 다시 검증한다.
- 상품 삭제는 이번 범위가 아니다. 카탈로그 상품 누락/잘못된 ID는 데이터 연결 오류로 처리하고 과거 단가를 현재 단가처럼 대신 사용하지 않는다. 원문 예외는 HTTP 응답에 노출하지 않는다.

## 조회와 대체 후보

추천 목록·상세는 구매요청 소유권을 확인한 뒤 현재 조건을 검증한다. 목록은 원래 rank, 숫자 후보 ID 순으로 정렬하고 사용할 수 없는 후보도 보존한다. 내부 Page의 totalCount는 전체 행 수, availableCount는 사용 가능한 전체 행 수다. 공개 total_count 집계 계약은 확인 후 DTO에 매핑한다. 페이지 기본값은 1/20이고 최대 크기는 생성자에서 명시한다.

대체 후보 조회는 예약 소유권, 실패 여부, 구매요청 소유권, 선택 후보와 구매요청 연결을 확인한다. 실패 여부는 BE1의 확정 enum을 어댑터에서 boolean으로 변환한다. 같은 구매요청에 이미 저장된 후보만 사용하며 원래 후보 ID와 같은 상품 쌍(역순 포함)을 제외한다. 거절 점포의 다른 상품은 남긴다. 같은 대체 상품 쌍은 원래 순위가 앞선 하나만 남기며, 현재 조건을 통과한 후보만 걸러낸 뒤 페이지와 개수를 계산한다. 선택 후 새 예약은 BE1 기존 API를 사용한다.

대체 추천의 기준 수령시각은 아직 합의가 필요하므로 호출자가 `validationPickupAt`을 명시해야 한다. null이면 실패하며 원 구매요청/예약 시각 중 하나를 임의 선택하지 않는다. GET 로직에 AI 재호출, 예약 생성, 상태 변경은 없다.

결과가 존재하지만 요청 페이지 밖이라 빈 목록이면 '추천 없음' 메시지를 붙이지 않는다. 전체 후보 없음과 전체 사용 불가도 구분한다. 내부 오류/메시지는 공개 응답 계약과 공통 예외에 연결하기 전의 표현이다.

## 남은 연결 작업

1. 공유 Entity·Repository에 CatalogSource/RecommendationReadSource를 구현하고 필요한 조회 쿼리를 작성한다.
2. BE2 검증의 타입·사유 enum을 ProductEligibility에 연결한다. 조회 조건 일관성과 쿼리 수를 실제 DB로 검사한다.
3. BE1 세션·CUSTOMER 권한·공통 예외/응답과 공개 DTO·컨트롤러를 연결한다. 이 내부 Long ID 스냅샷을 직접 응답하지 않고 문자열 ID DTO로 변환한다.
4. [비동기 추천 상태 처리](be3-async-recommendation.md)의 실행·상태 전이·복구 기반은 구현했다. 공유 스키마에 선점과 기한을 영속화하고 후보 저장·상태 전이 트랜잭션을 연결해야 한다.
5. 대체 수령시각, 공개 사유 코드·total_count, 상품당 수량, 후보 상한을 확정한다. 고객 상품·점포 상세 전체 API도 아직 공유 조회 어댑터가 필요하다.

## 검증

CandidateGeneratorTest, CandidateRevalidatorTest, RecommendationReadServiceTest는 메모리 스냅샷과 가짜 공통 검증으로 위 경계를 검사한다. 생성 → 기존 순위화 → 가격 변경 후 재검증 흐름도 포함한다. 실제 MySQL, 세션, BE2 서비스, 외부 AI 호출은 검증하지 않는다.

```powershell
.\gradlew.bat spotlessCheck build
```
