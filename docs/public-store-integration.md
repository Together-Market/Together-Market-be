# 공공데이터 수집·매칭 (#17)

수집은 public-data 프로필에서만 실행한다. 사용자용 REST API는 추가하지 않는다.
V4는 source_record_key(CHAR64 nullable) 및 (source,source_record_key) UNIQUE를 추가한다. V1/V2는 수정하지 않는다.

## 설정과 첫 실행

DB_URL/DB_USERNAME/DB_PASSWORD 및 두 서비스의 원본 일반 인증키를 SBIZ_SERVICE_KEY, YPAY_SERVICE_KEY 환경변수로 설정한다. 키를 명령행·Git·로그에 넣지 않는다. URL 인코딩은 클라이언트가 한 번 수행한다. 포털에 일반 인증키 하나만 표시되면 그대로 사용하며, %2B/%2F 등 이미 인코딩된 키도 정규화해 이중 인코딩을 방지한다.

```powershell
.\gradlew.bat bootRun --args='--spring.profiles.active=public-data --spring.main.web-application-type=none --public-data.source=YPAY'
```

기본 시작페이지1/페이지크기5/최대1페이지/dry-run=true. dry-run은 원문 저장을 하지 않지만 DB 연결과 Flyway는 실행된다.
SBIZ는 source=SBIZ, --public-data.district=41463 사용. 처인41461/기흥41463/수지41465 각각 수집한다. 업종 필터 없이 조회한다.

실제 저장은 dry-run=false로 실행한다. 수집일시는 공통 DB 정책인 KST(Asia/Seoul)를 기본 사용하며 다른 시간대는 거부한다.

```powershell
.\gradlew.bat bootRun --args='--spring.profiles.active=public-data --spring.main.web-application-type=none --public-data.source=YPAY --public-data.dry-run=false --public-data.storage-zone=Asia/Seoul --public-data.start-page=1 --public-data.page-size=100 --public-data.max-pages=1'
```

1페이지 검증 후 max-pages를 늘린다. 페이지당 트랜잭션, 호출간200ms/연결10초/응답30초. 실패 시 완료된 페이지는 유지되고 실패한 페이지부터 재실행한다. 자동 재시도·정기 실행은 추가하지 않았다.

## 변환·중복 처리

- SBIZ: bizesId를 external_id로 사용. rdnmAdr 우선/lnoAdr 대체. 좌표 소수점7자리 반올림.
- YPAY: no는 source_row_number. external_id/좌표 null. subcls 우선/fld 대체.
- 응답 성공코드와 페이지를 검사. XML 인증 오류도 안전한 오류로 중단. 원본 오류나 요청 URL을 로그에 출력하지 않는다.
- 이름·주소 없는 행 등 잘못된 레코드는 skipped로 집계한다. 숫자 결과 건수는 현재 조회 조건의 결과이며 영업 중 점포 수가 아니다.
- 와이페이는 정규화한 이름+주소 SHA256을 중복 키로 사용. 이름·주소 변경은 새 레코드가 될 수 있고, 동일 이름·주소의 다른 사업자는 구분할 수 없다.
- 공백·Unicode NFKC·경기/경기도 외에 괄호·동호수·지점은 제거하지 않는다.
- 기존 key=null 레코드는 출처+원천ID 또는 정확한 이름·주소가 유일할 때만 연결. 중복은 중단하여 별도 정리한다. 과거 다른 source 표기는 자동 통합하지 않는다.
- 재수집은 matched_store_id를 유지하며 stores/products를 갱신하거나 삭제하지 않는다.
- 와이페이는 갱신 지연 안내가 있어 현재 결제·영업 가능 여부를 보장하지 않는다.

## 명시적 매칭

DB에서 public_store_records.id를 확인한 뒤 아래처럼 후보를 조회한다. 숫자는 예시다.

```powershell
.\gradlew.bat bootRun --args='--spring.profiles.active=public-data --spring.main.web-application-type=none --public-data.operation=candidates --public-data.record-id=1'
```

이름+주소가 함께 일치하는 stores만 후보로 반환하며 자동 연결하지 않는다. 확인한 운영자가 연결:

```powershell
.\gradlew.bat bootRun --args='--spring.profiles.active=public-data --spring.main.web-application-type=none --public-data.operation=match --public-data.record-id=1 --public-data.store-id=3 --public-data.confirm=true'
```

해제는 operation=unmatch, record-id, confirm=true. 기존 다른 점포 연결은 먼저 해제해야 한다. 좌표를 복사하지 않는다. 이 명령은 DB 접근 가능한 담당자만 실행하며 외부 매칭 API는 없다.

## 검증 범위

사용자가 제공한 응답의 필드 구조를 본뜬 fixture/HTTP 대체 테스트 및 MySQL 저장·매칭 테스트를 사용한다.
실제 제공기관 재호출은 인증키가 없어 실행하지 않았다. SBIZ JSON은 header/body/items 배열과 items.item 구조를 지원하므로 최초 dry-run에서 실제 형식을 확인한다.
MySQL 테스트는 MYSQL_TEST_URL/USERNAME/PASSWORD 설정 후 --rerun-tasks로 실행한다.

## 검증 결과 (2026-11-13)

- spotlessCheck 및 전체 build --rerun-tasks 통과.
- 신규 테스트 12개 통과: 파서4, HTTP 대체2, 실행설정2, 실제 MySQL4. 건너뜀 없음.
- MySQL 8.4의 V3 적용 및 Entity 검증 통과. 동일/동시 수집 1건 유지, INSERT 오류 시 페이지 롤백, legacy 중복 중단, 명시 매칭·해제 및 재수집 연결 유지 확인.
- 실제 공공 API 인증키는 사용하지 않았으므로 제공기관 실호출·최신 가맹점 여부를 검증한 결과는 아니다.
## Repository 분리 및 Flyway 순서 (2026-11-14)

- PublicStoreImportService는 페이지 트랜잭션, 기존 중복 판단, 호출 순서를 담당한다.
- PublicStoreImportRepository는 기존 레코드 조회·수집 키 연결·MySQL upsert를 담당한다. MANDATORY로 Service 트랜잭션 없이 호출되지 않게 했다.
- Java 변수·메서드는 camelCase 유지. API JSON 규칙과 별개다.
- BE3 원격 feat/11-recommendation-persistence가 V3__add_recommendation_job_claim.sql을 사용하므로 공공데이터는 V4__public_store_collection_key.sql로 배정했다.
- 필수 병합/적용 순서: BE3 V3 포함 작업 → 공공데이터 V4. V4만 먼저 적용하면 나중의 낮은 버전 V3가 누락될 수 있어, V4 시작 시 BE3 열 존재를 검사한다. 없으면 ALTER 전에 중단한다.
- 현재 17번 브랜치에 BE3 PR #12의 V3가 dev 병합으로 반영됐다.
- 팀원에게 메시지를 보내거나 합의가 완료된 것은 아니다. PR에서 V3/V4 배정과 선행 병합 순서를 공유해야 한다.
- 옛 공공데이터 V3는 미커밋 상태였고 이전 검증 DB는 제거됐다. 별도 DB에 해당 V3를 적용한 경우에는 새 V4를 실행하기 전 이력/스키마를 확인해야 한다. 이력 삭제·repair·outOfOrder는 자동 적용하지 않는다.

### BE3 병합 후 실행

현재 소스에 V3가 포함되어 있다. 별도 테스트용 SPRING_FLYWAY_LOCATIONS 설정을 제거하고 기본 classpath:db/migration으로 V1~V4를 적용한다.

### 분리 후 검증 결과 (2026-11-14)

- Repository 분리 단위 테스트 3개 추가. 기존 공공데이터 12개를 포함해 관련 15개 테스트 통과.
- 원격 BE3 V3를 테스트 전용 위치에서 읽어 V1→V2→V3→V4 순서로 실제 MySQL 8.4 적용 및 Entity 검증 통과.
- spotlessCheck 및 전체 build --rerun-tasks 통과. 기존 페이지 롤백/동시 upsert/매칭 유지 테스트도 통과.
- 선행 BE3 열이 없는 별도 임시 DB에서는 V4가 ALTER 전에 실패하고 source_record_key가 추가되지 않음을 확인.
- 공유 DB/원격 브랜치는 변경하지 않았고, 검증용 컨테이너만 제거한다. 커밋·푸시는 수행하지 않았다.
## 로그인·추천 PR 연동 검토 (2026-11-14)

- #19의 세션 OwnerAccess를 사용한다. 공공데이터는 운영자 콘솔 작업이며 추가 HTTP API가 없다. 웹 보안 필터는 SERVLET 환경에서만 생성한다.
- #12 추천은 참여 점포의 products/stores 및 공통 상품 검증을 사용한다. 공공데이터로 참여 점포·상품을 자동 생성하지 않는다. 매칭은 출처 연결이며 현재 추천 응답에 공공데이터를 자동 표시하는 기능은 없다.
- #21/#23 예약의 상품·주소 사본은 공공데이터 재수집으로 변경되지 않는다.
- 미병합 #25는 추천 활성화 시 Clock 충돌 및 0원 상품 정책을 수정한다. 추천 활성화 통합 검증 전에 병합 여부를 확인한다. 17번에서 해당 코드를 중복 구현하지 않는다.