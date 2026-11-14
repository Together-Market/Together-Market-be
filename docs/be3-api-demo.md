# BE3 HTTP API · Gemini · 학습 모델 시연

이슈 #4, [API 명세서BE3](https://app.notion.com/p/417d8362bc0380c38eb9fa52c1541be6) 기준으로 내부 서비스에 HTTP·BE1 세션 경계를 연결한다. dev의 BE2 수령 검증과 BE1 예약을 사용한다. 추천·예약 Clock을 함께 활성화하려면 [PR #25](https://github.com/Together-Market/Together-Market-be/pull/25)의 명시 주입 수정이 먼저 필요하다. 해당 PR의 예약 코드와 테스트는 이 변경에 중복 포함하지 않는다.

## 실행

Java 21과 MySQL이 필요하다. 시연 DB의 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`를 현재 터미널 환경변수에 설정한 후 저장소 루트에서 실행한다.

```powershell
./scripts/run-demo.ps1
```

`GEMINI_API_KEY`가 없으면 숨김 입력으로 받는다. 키를 코드·노트북·설정 파일에 기록하지 않는다. 실행 스크립트는 종료 시 기존 환경변수를 복원한다. 기본 API 모델은 `gemini-3.5-flash-lite`이며 이 학습 가중치와 다른 `GEMINI_MODEL`을 지정하면 시작에 실패한다. 배포 환경에서는 비밀 관리 시스템으로 키를 전달한다.

`demo` 프로필은 추천 HTTP, DB 저장, 비동기 작업, 고객 조회, Gemini, 시연 가중치를 함께 켠다. DB 연결·Flyway는 기존 설정을 따른다. 카탈로그 자동 입력은 기본 OFF이므로 시연 DB에는 BCrypt 고객/점주 계정, 서로 다른 두 점포, 상품·목적 태그, BUSINESS/PICKUP 일정이 있어야 한다. REC-03 좌표 누락은 HTTP 200, `distance_meters=null`, `location_status=UNAVAILABLE`이며 좌표를 임의 생성하지 않는다. [최신 주소·좌표 명세](kakao-geocoding-api.md)를 참고한다.

## 연결한 API

| API | 경로 |
| --- | --- |
| PUR-01 | GET /purpose-categories |
| PUR-02 | GET /purpose-categories/{category_id}/questions |
| PUR-03 | POST /purchase-requests |
| PUR-04 | GET /purchase-requests/{request_id} |
| REC-01 | GET /purchase-requests/{request_id}/recommendations |
| REC-02 | GET /recommendations/{candidate_id} |
| REC-03 | GET /recommendations/{candidate_id}/locations |
| PRO-01 | GET /products/{product_id} |
| STO-01 | GET /stores/{store_id} |
| RES-04 | GET /reservations/{reservation_id}/alternatives |

모두 CUSTOMER 세션을 사용한다. 고객 ID·역할은 BE1 LoginUser에서 읽으며 요청에서 받지 않는다. POST는 기존 CSRF 필터를 통과해야 한다. 성공 ApiResponse와 실패 ApiError를 사용한다. ID는 문자열, 금액은 원 단위 정수, 날짜시간은 +09:00, 페이지는 1부터·기본 size 20·상한 100이다.

생성은 202/RECOMMENDING을 즉시 반환한다. 커밋 후 비동기 작업이 READY 또는 FAILED로 종료한다. 후보가 없는 경우 READY와 빈 목록이며 AI 오류와 구분한다. 목록/상세는 현재 가격·판매·수령 조건을 재검증한다. 일반 목록의 total_count는 불가 후보도 포함하고, 대체 목록은 검증을 통과한 후보만 집계한다. 불가 후보에는 availability_status=UNAVAILABLE 및 unavailable_reasons[{product_id,code}]를 제공한다. 상품/점포 삭제는 범위 밖이며 연결 데이터가 사라진 경우 409 RECOMMENDATION_DATA_UNAVAILABLE로 처리한다.

목록·상세의 가격과 상품 정보는 동일한 읽기 트랜잭션에서 조회한다. 추천 후보가 당시 가격과 달라도 예산과 조건을 만족하면 선택 가능하다. 최종 가격·조건 검증, 수량 1, 예약 당시 스냅샷·멱등성은 BE1이 처리한다. GET 대체 추천은 AI 재호출·예약 생성·예약 상태 갱신을 하지 않는다.

## 시연에 적용한 초안 정책

아래 값은 정식 서비스 정책이 확정됐다는 뜻이 아니다. 시연 프로필에서만 명시적으로 제공한다.

- 중분류 22개의 다섯 번째 질문: 기존 purchase-reference.json 초안 문구. 일반 실행은 별도의 승인된 preference-labels 설정이 필요하다.
- 대체 추천: 원 구매요청의 preferred_pickup_at으로 재검증한다. 기존 실패 예약에서 선택한 후보·동일 상품 조합만 제외한다. 거절 점포의 다른 상품은 일괄 제외하지 않는다.
- 상품 상세: ON_SALE/SOLD_OUT/PAUSED 모두 조회 가능. 좌표 누락은 상세 null, REC-03은 200과 거리 null/위치 UNAVAILABLE.
- 예약 승인 제한시간: 시연 기본 30분, RESERVATION_APPROVAL_WINDOW로 변경. 실제 마감은 BE1 정책대로 준비 시작 가능시각과 비교해 더 이른 시각이다.
- FE가 다른 origin이면 별도 CORS·쿠키 배포 설정이 필요하다. 현재 기본은 동일 origin이다.

## 학습 모델 출처와 제한

`src/main/resources/recommendation/demo/gemini-ranker.json`은 2026-11-14 Colab에서 학습한 원본 가중치다. 40개 상황·120개 후보를 실제 Gemini API로 평가하고 고정된 AI 임시 선호 순위로 학습했다. 유효 후보 111개, 학습 상황 23개·독립 비교 57개다. 최종 평가 8개에서 1위 일치 7/8, 쌍 비교 19/22로 기본 가중치와 같았다. 고객 선호 개선이나 Gemini 자체 미세조정 결과로 설명하지 않는다.

원본의 `mode=ai_synthetic_experiment`, `deployable=false`, `label_source=ai_synthetic`를 보존한다. 로더의 명시적 demo 경로만 이를 읽고 내부 RankingModel.mode를 demo로 반환한다. trained 경로로는 거부하고, demo 프로필이 없거나 prod/production 프로필이 함께 있으면 거부한다. 모델 제공자·모델명·프롬프트·특징 버전과 6개 유한 가중치를 확인하며 실패 시 baseline으로 조용히 전환하지 않는다.

호출 구조: 구매조건 저장 → 실제 DB 후보와 BE2 검증 → Gemini 의미 점수 → 학습 가중치 계산·다양성 정렬 → 현재 조건 재검증·DB 저장 → 고객 선택 → BE1 통합 예약.

## 검증 방법

`./gradlew.bat spotlessCheck build`로 단위·설정·HTTP 회귀를 실행한다. 전용 MySQL의 MYSQL_TEST_URL/MYSQL_TEST_USERNAME/MYSQL_TEST_PASSWORD를 설정하면 실제 저장·조회·동시성 및 DemoWorkflowDatabaseIntegrationTest를 실행한다. 서비스 DB를 테스트에 사용하면 안 된다.

DemoWorkflowDatabaseIntegrationTest는 기본적으로 외부 AI만 고정 응답으로 대체하고 실제 학습 모델·세션·CSRF·DB·비동기 작업·예약·점주 승인을 사용한다. 라이브 검증은 LIVE_GEMINI_TEST=true, LIVE_GEMINI_KEY를 프로세스 환경으로 전달하고 happyPathThroughActualRankingReservationAndBothOwners 테스트만 지정한다. 비용이 발생하므로 기본 전체 테스트에서는 라이브 API를 호출하지 않는다.

2026-11-14 API 연결 당시 로컬 검증 기록(현재 PR의 재검증 결과는 PR 본문 참조):

- Java 21, MySQL 8.4.13 전용 테스트 DB에서 spotlessCheck/build 및 전체 250개 테스트 통과. 실패·오류·생략 0개.
- 실제 Gemini 연결을 사용한 전체 HTTP 흐름 1회 추가 통과: CSRF·로그인 → 질문·구매요청 202 → 비동기 READY → 시연 학습 모델 추천·상품·점포·지도 → 예약 및 동일 키 재응답 → 두 점주 승인 → 새 예약의 점주 거절 → 기존 대체 후보 조회.
- 타인 구매요청·추천·지도·대체 후보 차단, 현재 가격 변경, 판매 중지 UNAVAILABLE, AI 실패/빈 후보 구분을 검사했다. 외부 API 오류 시나리오는 고정 응답으로 재현했다.
- 실험 모델 파일 SHA-256: `79919d99a1959b8ec52e7f34c4e571dbc20062892939e4546442b01ad5b56cde`. Colab 학습 원본과 동일하다.
- 테스트 종료 후 임시 MySQL 서버를 종료했다. 운영 배포·GitHub 푸시·프론트 화면 연결 완료를 의미하지 않는다.
