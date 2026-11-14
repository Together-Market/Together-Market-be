# 카카오 주소 미리보기와 점포 좌표 계약

2026-11-15 구현. 기준 브랜치 `dev`, 분석 시작 커밋 `0cea432`. DB 스키마 변경 없음.

## 이번에 확정한 범위

사용자는 카카오맵 사용 설정을 ON으로 저장했으며, 로컬 미리보기 API의 KAKAO 출처·storage_allowed=false 성공 응답을 직접 확인했다. 실제 키는 사용자만 입력했고 에이전트는 읽거나 출력하지 않았다.

카카오 지도 표시와 도로명 주소 선택 UI는 프런트 담당이다. 이 문서의 API는 **선택이 끝난 주소 문자열을 좌표로 변환하는 실시간 미리보기**다. 주소 자동완성/주소 선택 UI 자체를 제공하지 않는다.

카카오 [공식 FAQ](https://devtalk.kakao.com/t/faq-api/125610)는 로컬 API 결과의 별도 저장 사용을 제한한다. [좌표 저장 관련 공식 답변](https://devtalk.kakao.com/t/api/140690)도 저장을 허용하지 않는다고 안내한다. 사용자와 협의하여 **공공데이터 좌표 보완을 우선 완성하고 카카오 결과 저장은 차단**했다. ON 설정·유료 쿼터·사용자 확인 버튼은 저장 권한을 의미하지 않는다.

- 카카오 좌표를 DB·CSV·배치 보고서·서버 캐시에 저장하지 않는다.
- `storage_allowed`는 항상 `false`다. 저장 차단을 해제하는 환경변수는 없다.
- 카카오 자동 좌표 저장은 이번 배포 기능에 포함되지 않는다. 별도로 저장 가능한 제공자/근거를 확보한 뒤 구현을 이어가야 한다.
- 기존에 저장 권한이 있는 공공데이터·직접 확보한 좌표를 전달하는 계약은 유지한다. 카카오 응답의 출처를 바꾸어 보내는 방식으로 제한을 우회하면 안 된다.

## 공통 규칙

경로에 `/api/v1` 없음. JSON/쿼리는 snake_case. ID는 JSON 문자열. 성공은 `{ "data": ... }`, 실패는 `{ "code": "...", "message": "...", "errors": [] }`다. 입력 오류의 errors에는 `field`, `reason`이 포함된다. 세션 OWNER 권한을 사용한다. POST/PATCH는 기존 `X-CSRF-TOKEN`과 같은 세션이 필요하다. GET에는 CSRF 헤더가 필요하지 않다.

좌표는 WGS84 숫자이며 위도 -90~90, 경도 -180~180, 입력은 소수점 7자리까지다. 카카오 응답은 문자열 x=경도, y=위도로 읽고 BigDecimal HALF_UP으로 7자리까지 반올림한다. 좌표가 하나라도 없는 쌍은 사용하지 않는다. 실패 시 (0, 0)을 만들거나 저장하지 않는다.

## 점주 주소 미리보기 — 새 경로

```http
GET /owner/stores/geocode?address=경기도%20용인시%20...
```

Request body 없음. `address`는 필수, 공백 제외 1~500자, 제어문자 불가. 양끝 공백과 중복 공백을 정리한다. 프런트는 `URLSearchParams({ address })`로 인코딩한다. 기존 GET `/owner/stores`와 PATCH `/{store_id}`와 충돌하지 않는다.

HTTP 200, `Cache-Control: no-store`:

```json
{
  "data": {
    "address": "사용자가 선택한 주소",
    "latitude": 37.2758538,
    "longitude": 127.1512813,
    "coordinate_source": "KAKAO",
    "storage_allowed": false
  }
}
```

위 숫자는 응답 구조 예시이며 특정 주소의 실조회 결과가 아니다. `address`는 정리된 요청 주소이며 카카오가 보증한 정규 주소가 아니다.

카카오 `/v2/local/search/address.json`을 `analyze_type=exact&page=1&size=2`로 요청한다. 전체 검색 건수가 1이고 건물/지번 단위(`ROAD_ADDR`/`REGION_ADDR`)인 결과만 사용한다. 여러 결과에서 첫 항목을 임의 선택하지 않는다. 접속 제한 2초, 요청 제한 3초. 자동 재시도·리다이렉트 추적 없음.

| HTTP | code | 프런트 처리 |
| --- | --- | --- |
| 400 | VALIDATION_ERROR | 주소 입력·길이 확인 |
| 401 | UNAUTHORIZED | 점주 로그인 |
| 403 | FORBIDDEN | OWNER 계정 사용 |
| 422 | ADDRESS_NOT_FOUND | 다른 표기/정확한 주소 선택 |
| 422 | ADDRESS_AMBIGUOUS | 건물번호·지번까지 구체화 후 재조회 |
| 422 | ADDRESS_INCOMPLETE | 시/동/도로명만 보내지 않고 건물 주소 선택 |
| 503 | GEOCODE_NOT_CONFIGURED | 키 누락, 카카오 401/403: 서버 키·사용 활성화·호출 IP 확인 |
| 503 | GEOCODE_RATE_LIMITED | 연속 재호출 중단, 앱 쿼터 확인 |
| 503 | GEOCODE_UNAVAILABLE | 요청 중단, 이후 사용자가 다시 시도 |
| 504 | GEOCODE_TIMEOUT | 안내 후 명시적으로 재시도 |
| 502 | GEOCODE_UPSTREAM_ERROR | 외부 HTTP/통신/JSON/잘못된 좌표 응답 |

외부 본문, Authorization 헤더, 키, 예외 원문/cause는 응답·로그로 전달하지 않는다. 공급자 인증 실패는 사용자 세션 오류(401)로 위장하지 않는다. 실제 키 없는 테스트에서는 HTTP 호출을 전부 대체한다.

## OWN-02 — POST /owner/stores

기존 필드 이름과 성공 HTTP 201/응답 구조는 유지한다.

```json
{
  "name": "점포명",
  "business_type": "카페",
  "address": "저장할 점포 주소",
  "latitude": 37.2758538,
  "longitude": 127.1512813,
  "phone": "010-0000-0000",
  "coordinate_source": "PROVIDED"
}
```

숫자는 형식 예시다. 해당 주소의 좌표를 저장 가능한 출처에서 확인한 다음 사용한다.

- `name`, `business_type`, `address`, `phone`: 기존과 같이 필수. 길이 150/100/500/30, null·공백 금지.
- `coordinate_source`: 새 선택 필드. 생략하면 `PROVIDED`, 허용 값은 `PROVIDED`, `KAKAO`다. DB 컬럼으로 저장하는 출처 이력은 아니다.
- `PROVIDED`는 클라이언트가 주소에 맞는 저장 가능한 좌표를 확보했다는 계약이다. 서버가 주소와 실물 위치의 일치까지 확인한 것은 아니다.
- 좌표가 둘 다 있으면 기존처럼 저장한다. (0, 0)은 400으로 거절한다.
- 둘 다 생략/null 또는 하나라도 null이면 기존 값과 섞지 않는다. 자동 변환 **저장**이 필요하지만 현재 카카오 저장이 차단되어 HTTP 503 `GEOCODE_STORAGE_NOT_ALLOWED`, 점포는 생성하지 않는다.
- 좌표 필드 하나만 보내면 400 `VALIDATION_ERROR`다. 숫자 문자열/범위/정밀도 오류도 400이다.
- `coordinate_source=KAKAO`는 두 좌표가 있어도 503 `GEOCODE_STORAGE_NOT_ALLOWED`다. 실제 외부 호출 없이 거절한다.
- `owner_id`, `id`, 미리보기 응답의 `storage_allowed` 등 다른 필드는 400이다.

성공 data: `id`, `name`, `business_type`, `address`, `latitude`, `longitude`, `phone`, `owner_id`. ID는 문자열이다.

**호환 변경:** 기존의 여섯 필드를 모두 요구하던 파싱 규칙에서 좌표 생략은 허용한다. 다만 예전의 null 좌표 등록 성공은 더 이상 허용하지 않는다. 카카오 미리보기 응답 전체를 등록 body에 그대로 복사하면 안 된다.

## OWN-03 — PATCH /owner/stores/{store_id}

HTTP 200. 기존 본인 점포 검증과 비관적 잠금 유지. 없는 점포/다른 소유자는 동일 404 `NOT_FOUND`다.

| 요청 | 결과 |
| --- | --- |
| 전화번호·이름 등만 수정 | 주소/좌표 유지, 외부 호출 없음 |
| 주소를 이전과 정확히 같은 문자열로 보냄, 좌표 생략 | 좌표 유지 |
| 주소 변경 + 새 좌표 두 개 (`PROVIDED`) | 주소와 좌표를 함께 저장 |
| 주소 변경 + 좌표 생략/null | 503 `GEOCODE_STORAGE_NOT_ALLOWED`, 모든 변경 취소 |
| 좌표 하나만 보냄 | 400, 기존 좌표와 조합하지 않음 |
| 좌표 두 개 모두 null 또는 하나 null | 503, 좌표 삭제/부분 교체 없음 |
| `coordinate_source=KAKAO` | 503, 모든 변경 취소 |

미입력 필드를 유지하는 PATCH 동작은 그대로다. 위치와 관계없는 수정은 기존 누락 좌표를 보유한 점포에도 가능하다. 기존 두 좌표가 모두 null인 상태를 삭제 용도로 PATCH하던 클라이언트는 수정해야 한다.

프런트는 주소를 편집하는 즉시 이전 미리보기와 좌표를 무효화한다. `PROVIDED` 저장에서도 새 주소에 대해 두 좌표를 함께 확정해 보내야 한다. 저장 성공 응답의 좌표로 화면을 갱신한다. 카카오 미리보기는 현재 저장 불가능하므로 **미리보기 확인만으로 저장 버튼을 활성화하지 않는다**. 이 방식으로 카카오 미리보기와 다른 위치가 조용히 저장되는 일을 막는다. 카카오 미리보기→동일 위치 영구 저장 경로는 차단 상태이며 완성된 것으로 간주하지 않는다.

## REC-03 — GET /recommendations/{candidate_id}/locations

Request body 없음. 기존 CUSTOMER 세션·후보 소유권·서로 다른 두 점포 구성 검증 유지. 고객 조회에서는 외부 API를 호출하지 않는다.

```json
{
  "data": {
    "recommendation_candidate_id": "123",
    "stores": [
      {"id":"10","name":"점포 A","business_type":"카페","address":"주소 A","latitude":null,"longitude":null,"phone":"031-000-0000"},
      {"id":"11","name":"점포 B","business_type":"꽃집","address":"주소 B","latitude":37.2758538,"longitude":127.1512813,"phone":"031-000-0001"}
    ],
    "distance_meters": null,
    "distance_type": "STRAIGHT_LINE",
    "location_status": "UNAVAILABLE"
  }
}
```

- `distance_meters`: Java `Long`, JSON 정수 또는 null. 양쪽 점포 좌표가 모두 유효하면 기존 반올림 직선거리 정수, `location_status=AVAILABLE`.
- 한 점포에서라도 위도/경도 중 하나가 null/범위 오류이면 그 점포의 두 값을 모두 null로 응답한다. 다른 점포의 정상 좌표는 유지한다.
- 좌표 누락은 이제 HTTP 200 + `UNAVAILABLE`다. 이전 `409 STORE_LOCATION_UNAVAILABLE` 처리만 하던 프런트는 바꿔야 한다.
- `distance_type=STRAIGHT_LINE`는 계산 방식이며, 위치 확인 가능 여부는 `location_status`로 판정한다. 도보 시간/경로가 아니다.
- demo의 coordinate-policy 이름은 `DETAIL_NULL_MAP_NULL`로 정리했다. 기존 `DETAIL_NULL_MAP_ERROR` 값도 호환 별칭으로 받지만 REC-03의 실제 응답은 이제 위 null 계약을 따른다. `ALL_COORDINATES_REQUIRED`는 상품/점포 상세에서 좌표를 요구하는 기존 정책을 유지한다.
- 정상 좌표가 같은 두 점포의 실제 거리 0은 가능하다. `if (!distance_meters)`로 누락을 판단하지 말고 `distance_meters === null`로 비교한다.
- 다른 사용자 후보 403, 후보 없음 404, 상품/점포 구성 손상 409는 기존 오류다.

프런트는 UNAVAILABLE이면 `위치 확인 불가`를 표시하고 두 점포 연결선·거리·경로 UI를 숨긴다. 좌표가 유효한 점포만 마커를 표시할 수 있다. null을 0으로 치환하지 않는다. 위치 AVAILABLE은 **예약 가능**을 의미하지 않으며 추천 상세의 `availability_status`도 확인한다.

## 추천 거리 검증 영향과 남은 경계

기존 후보 생성·결과 저장·추천 재조회는 누락/잘못된 좌표와 500m 초과를 이미 제외한다. 추천 재조회에서는 `UNAVAILABLE`, `STORE_DISTANCE_EXCEEDED_OR_UNKNOWN`을 반환한다. 이 규칙은 그대로 유지한다. 따라서 누락 좌표가 많으면 후보가 줄거나 빈 추천 목록이 생길 수 있다. 이번 보완은 기존 카탈로그/상품/태그/일정 수를 바꾸지 않고 이 원인을 줄인다.

`ReservationService`의 최종 신규 예약 생성에도 500m 검증을 연결했다. 두 점포 행을 잠그고 최신 좌표를 refresh한 뒤 반올림 전 거리로 판정한다. 좌표 누락·범위 오류·500m 초과이면 409 `STORE_DISTANCE_EXCEEDED_OR_UNKNOWN`이며 예약·주문을 저장하지 않는다. 이미 성공한 동일 키·동일 요청의 재응답은 이 검증보다 먼저 처리하여 유지한다. 자세한 계약은 [예약 API](BE1_RESERVATIONS.md)를 참고한다.

## 프런트 전달 요약

1. 카카오 JavaScript 키는 프런트에서만 지도 표시용으로 사용. REST 키는 프런트로 보내지 않는다.
2. 도로명 주소 선택 UI 완료 후 OWNER 세션으로 geocode GET 호출. 입력할 때마다 호출하지 않고 선택 완료 시 호출한다.
3. 주소 편집 시 이전 결과를 폐기하고 늦게 도착한 이전 주소 응답도 무시한다.
4. `storage_allowed=false`인 카카오 결과는 화면 미리보기만 사용. DB 저장·localStorage·재사용 캐시 금지.
5. 점포 저장은 저장 가능한 출처에서 확인한 완전한 좌표 쌍으로 진행. 주소 변경 시 두 좌표 동시 전송.
6. REC-03의 nullable 거리와 `location_status` 반영. 거리 0과 null 구별.
7. 프런트는 `http://localhost:5173`에서 실행하고 백엔드만 배포한다. 백엔드 환경변수 `CORS_ALLOWED_ORIGINS=http://localhost:5173`, 프런트 요청의 credentials, 기존 CSRF 절차를 함께 적용한다. 배포 HTTPS 서버의 쿠키 설정과 실제 브라우저 검증 순서는 [배포 안내](kakao-setup-deployment.md)를 따른다.

## 검증

Java 21에서 `spotlessApply spotlessCheck build --offline --no-daemon` 성공. 카카오 HTTP는 Mockito로 대체했다. 주소/좌표 매핑·정밀도·무결과·복수결과·잘못된 응답·타임아웃·쿼터·키 비노출, 실제 보안 필터의 OWNER/CSRF, 원자적 수정 실패, REC-03 JSON null을 검사한다. 실제 MySQL·카카오 호출은 실행하지 않았다. MySQL 조건부 테스트는 테스트 전용 DB에서 사용자가 실행하거나 기존 CI가 검증한다.

최종 실행(CORS·예약 최종 거리 보완 포함): 총 378개, 통과 324개, 조건부 생략 54개, 실패/오류 0개. Java 21로 `spotlessApply spotlessCheck build --offline --no-daemon` 및 `git diff --check` 통과. CORS 허용/거부·세션·CSRF는 MockMvc로 검증했고 실제 배포 브라우저 연결은 아직 확인하지 않았다. 별도 이슈/브랜치 생성, 커밋·푸시·병합, 실제 데이터 보완과 배포는 실행하지 않았다. [배포 전 검토](predeployment-review.md), [사용자 Git 진행 순서](git-release-guide.md)를 참고한다.
