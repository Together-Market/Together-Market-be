# 기존 점포 좌표 보완 실행 안내

DB 접속과 모든 실행은 사용자가 직접 한다. 이번 구현 과정에서는 실제 DB를 조회하거나 변경하지 않았다. 시연 점포 598개라는 기존 관찰을 현재 `stores` 총수/누락 건수로 단정하지 않는다.

## 보완 규칙

- `stores.latitude IS NULL OR stores.longitude IS NULL`만 대상으로 ID 오름차순 조회. 두 좌표가 모두 있는 점포는 덮어쓰지 않는다. 범위 오류/기존 (0, 0)도 이 일괄 보완에서는 자동 교정하지 않고 별도로 확인한다.
- `public_store_records.matched_store_id=stores.id`로 이미 연결된 행만 사용. 연결 관계를 자동 생성하거나 변경하지 않는다.
- 주소를 NFKC/공백/`경기`→`경기도`로 정리해 비교한다. 다른 주소에 연결된 오래된 자료는 사용하지 않는다. 상세 층·호수 등의 차이는 안전하게 불일치 처리하므로 수동 확인이 필요할 수 있다.
- 공공데이터 위도·경도가 모두 있고 범위가 맞으며 (0, 0)이 아니어야 한다. 해당 공공데이터 출처의 이용 허용 범위도 유지한다.
- 연결된 유효 좌표가 여러 개라도 값이 같으면 한 번 처리. 서로 다르면 `PUBLIC_COORDINATES_CONFLICT`로 실패한다.
- 기존 위도 하나만 남아 있어도 공공데이터의 **두 좌표를 함께** 적용한다.
- 공공데이터를 사용할 수 없으면 `NO_USABLE_PUBLIC_COORDINATES`. 카카오 결과 영구 저장으로 대체하지 않는다.
- 적용 직전 주소·기존 위도/경도·선택한 공공데이터 행과 연결 상태를 SQL 조건으로 재검사한다. 변경되었다면 `SKIPPED_CONCURRENT_CHANGE`다.
- 전체를 한 트랜잭션에 넣지 않는다. 점포 하나의 좌표 한 쌍이 한 UPDATE로 반영된다. 중단 전 성공한 행은 유지되며 재실행 시 건너뛴다. 상품·태그·일정·점포 수, 공공데이터 행은 바꾸지 않는다.
- 미리보기는 좌표 UPDATE를 호출하지 않는다. 전용 프로필은 Flyway/SQL 초기화/카탈로그 입력/예약 만료 스케줄러/추천 실행을 끈다. 다른 프로필과 섞지 않는다.

## 1. 백업과 누락 개수 확인

가능하면 기존 서버를 잠시 중지하고 팀원에게 점포 수정 중단을 알린다. IntelliJ 하단 Run 창에서 실행 중 서버의 빨간 정지 버튼을 누른다.

MySQL Workbench를 사용하는 경우:

1. Workbench 시작 화면에서 기존 DB 연결 카드를 더블클릭한다. 비밀번호는 본인 화면에서 입력한다.
2. 왼쪽 `SCHEMAS`에서 서비스가 사용하는 스키마 이름을 확인한다. 예를 들어 `together_market`을 더블클릭해 기본 스키마로 지정한다. 실제 이름이 다르면 실제 이름을 사용한다.
3. 상단 `Server` → `Data Export`를 연다. 해당 스키마와 전체 테이블을 선택하고 데이터와 구조를 함께 내보내는 `Dump Structure and Data`를 선택한다.
4. `Export to Self-Contained File`을 선택한다. Git 저장소 밖의 본인 백업 폴더에 파일명을 입력한다. `Start Export`를 누르고 완료/오류 여부를 확인한다. 백업은 로컬에 보관한다.
5. SQL 편집기로 돌아와 `File` → `Open SQL Script`에서 `C:\Together-Market-be\scripts\inspect-store-coordinates.sql`을 연다.
6. 첫 문장의 `SELECT DATABASE()`에 커서를 두고 `Ctrl+Enter`로 실행한다. 결과가 의도한 스키마인지 확인한다.
7. 그다음 문장을 하나씩 `Ctrl+Enter`로 실행한다. `missing_pair_count`, `both_null_count`, `half_null_count`와 각 테이블 row_count를 기록한다.
8. 연결된 공공데이터 좌표가 있는 개수는 주소 일치/충돌 검증 전의 참고 상한이다. 실제 보완 가능 수는 다음 미리보기에서 확인한다.

[Workbench 공식 내보내기 안내](https://dev.mysql.com/doc/workbench/en/wb-admin-export-import-management.html).

## 2. IntelliJ에 미리보기 실행 설정 추가

기존 서버의 DB 환경변수가 OS 전역에 있다고 가정하지 않는다. 기존 실행 설정에만 입력했다면 새 설정에도 필요하다. 비밀값은 사용자만 복사/입력한다.

1. IntelliJ 상단 `Run` → `Edit Configurations...`를 누른다.
2. 현재 서버를 실행하는 **Gradle** 설정을 선택한다. `Copy Configuration`/복사 아이콘으로 복제한다. 복사 기능이 없으면 왼쪽 `+` → `Gradle`을 선택한다.
3. Name에 `coordinates-preview`를 입력한다. `Gradle project`는 `C:\Together-Market-be`다.
4. `Run` 또는 `Tasks and arguments` 칸을 아래 내용으로 **교체**한다. 기존 `--spring.profiles.active=demo`를 함께 남기지 않는다.

```text
bootRun --args="--spring.profiles.active=coordinate-backfill --coordinate-backfill.dry-run=true --coordinate-backfill.after-store-id=0 --coordinate-backfill.limit=10"
```

5. `Environment variables`에서 기존의 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`가 올바른 대상 DB인지 본인이 확인한다. 누락 시 `...` → `+`로 Name/Value를 직접 입력한다. 기본 공공데이터 보완에는 카카오 키가 필요 없다.
6. 개인 환경변수가 포함되므로 `Store as project file`은 체크하지 않는다. `Apply` → `OK`를 누른다.
7. 상단 실행 설정에서 `coordinates-preview`를 선택하고 초록 실행 버튼을 누른다. 명령 종료 후 다음 형식의 행들을 확인한다.

```text
coordinate-backfill store_id=... status=WOULD_UPDATE source=PUBLIC_DATA reason=NONE latitude=... longitude=...
coordinate-backfill dry_run=true selected=10 last_store_id=... kakao_calls=0
```

`last_store_id`는 점포 개수가 아니라 이번에 검사한 마지막 DB ID다. 로그의 공공데이터 좌표/출처와 점포를 확인한다. `selected=0`이면 해당 범위에 누락 점포가 없다. 기본 100개, 처음에는 예시처럼 10개가 적절하다.

Gradle의 `BUILD SUCCESSFUL`은 명령 실행이 끝났다는 뜻이다. 모든 점포가 보완됐다는 뜻이 아니므로 반드시 각 행의 `status`와 `reason`을 확인한다.

터미널에 DB 환경변수를 이미 직접 설정한 경우에만 같은 작업을 아래처럼 실행할 수 있다. IntelliJ 실행 설정의 환경변수가 터미널로 자동 전달되는 것은 아니다.

```powershell
Set-Location C:\Together-Market-be
.\gradlew.bat bootRun --args="--spring.profiles.active=coordinate-backfill --coordinate-backfill.dry-run=true --coordinate-backfill.after-store-id=0 --coordinate-backfill.limit=10"
```

## 3. 같은 ID 범위를 실제 저장

백업과 미리보기 결과를 확인한 다음에만 실행한다.

1. `Run` → `Edit Configurations...`에서 `coordinates-preview`를 복사해 Name을 `coordinates-apply`로 지정한다.
2. 아래 문자열의 `마지막ID`를 앞서 출력된 `last_store_id` 숫자로 바꾼다. 글자/괄호를 그대로 넣지 않는다.

```text
bootRun --args="--spring.profiles.active=coordinate-backfill --coordinate-backfill.dry-run=false --coordinate-backfill.after-store-id=0 --coordinate-backfill.through-store-id=마지막ID --coordinate-backfill.limit=1000"
```

3. `Apply` → `OK` 후 `coordinates-apply`를 선택해 실행한다. 성공 행은 `UPDATED`, 동시에 변경된 행은 `SKIPPED_CONCURRENT_CHANGE`다.
4. 이 명령은 저장된 계획 파일을 실행하는 방식이 아니라 **현재 DB를 다시 검사**한다. 미리보기 이후 자료가 변경되면 결과/대상이 달라질 수 있다. 그러므로 작은 범위에서 점포 수정이 없는 시간에 실행하고 최종 결과를 확인한다.
5. Workbench에서 1단계 조회 SQL을 다시 실행한다. 누락 수는 성공한 만큼 줄고 점포·공공데이터·상품·태그·일정 행 수는 유지되어야 한다.
6. 다음 범위 미리보기는 `after-store-id`를 직전 `last_store_id`로 바꾸고 `through-store-id` 옵션을 제거한다. 미리보기→해당 범위 저장을 반복한다. 프로필/범위/저장 여부를 구분하려고 두 설정을 유지한다.

실제 저장은 카카오 미리보기 옵션과 함께 실행할 수 없다. `dry-run=false` + `preview-kakao=true` 조합은 오류로 종료한다.

## 4. 실패 확인·재실행

| status/reason | 의미와 다음 작업 |
| --- | --- |
| WOULD_UPDATE | 공공데이터로 보완 가능, 아직 저장하지 않음 |
| UPDATED | 좌표 두 개 저장 완료 |
| COORDINATES_PRESENT | 이미 두 좌표가 있어서 보존 |
| NO_USABLE_PUBLIC_COORDINATES | 연결 없음/주소 불일치/불완전·잘못된 좌표. SQL의 연결 행을 확인 |
| PUBLIC_COORDINATES_CONFLICT | 같은 주소에 서로 다른 좌표가 연결됨. 출처와 연결 관계를 수동 확인 |
| SKIPPED_CONCURRENT_CHANGE | 점포 또는 선택한 공공데이터가 조회 후 변경됨. 다시 미리보기 |
| DATABASE_ERROR | 해당 행에서 중단. DB 연결·권한 확인. 앞선 성공 행은 유지 |
| INTERRUPTED | 작업 중단. 앞선 성공 행은 유지 |

보고서의 `store_id`와 reason으로 실패를 찾는다. ID가 `N`인 점포 하나를 재검사하려면 `after-store-id=N-1`, `through-store-id=N`, `limit=1`을 숫자로 계산해 넣는다. ID는 연속일 필요가 없다. 실패 원인을 해결하기 전의 반복 실행은 같은 결과를 낸다.

처음부터 재실행해도 채워진 점포는 제외된다. 그러나 앞쪽 실패 행이 계속 남아 있으므로 전체 순회에는 last_store_id를 이용한다. 다음 범위로 이동하면 이전 실패는 자동 재시도되지 않으며 실패 ID를 따로 재검사한다. 여러 작업을 동시에 실행해도 조건부 UPDATE가 기존 쌍을 보호하지만, 불필요한 조회를 피하려고 한 작업만 실행한다.

## 5. 선택 사항: 저장 없이 카카오 조회 가능 여부만 확인

공공데이터를 사용할 수 없는 점포에 대해, 키 설정과 쿼터를 확인한 뒤 **사용자가 명시적으로** 아래 옵션을 미리보기 설정에 넣을 수 있다.

```text
bootRun --args="--spring.profiles.active=coordinate-backfill --coordinate-backfill.dry-run=true --coordinate-backfill.limit=10 --coordinate-backfill.preview-kakao=true --coordinate-backfill.max-kakao-calls=5 --coordinate-backfill.delay-millis=250"
```

이 경우에도 공공데이터가 우선이다. 카카오 검색 성공은 `PREVIEW_ONLY / KAKAO_STORAGE_NOT_ALLOWED`로 표시한다. 카카오 좌표 자체는 로그/보고서에 남기지 않고 즉시 폐기한다. 실패는 ADDRESS_NOT_FOUND, ADDRESS_AMBIGUOUS 등의 코드로 보고한다. 이 결과는 저장 예정 목록이 아니다.

- 한 실행에서 정리된 동일 주소는 결과 **상태**만 기억해 중복 호출을 피한다. 좌표 캐시는 만들지 않는다.
- 호출 시도 상한 기본 20, 최대 100. 간격 기본 250ms, 최소 100ms. 호출은 순차 처리한다.
- 쿼터/인증 설정/서버/시간 초과 등 5xx 내부 오류를 받으면 해당 실행의 추가 카카오 호출을 중단한다. 공공데이터 처리는 이어간다. 자동 재시도 없음.
- 별도 실행은 새 호출이다. 앱 전체 사용량은 카카오 개발자 사이트에서 확인한다. 자체 상한이 카카오 전체 쿼터를 대신하지 않는다.

## 6. 서버를 원래 설정으로 재실행

보완 명령이 끝나면 상단 실행 설정을 기존 서버 설정으로 돌려놓는다.

```text
bootRun --args="--spring.profiles.active=demo --demo.catalog.enabled=false --reservation.approval-window=PT30M"
```

좌표를 바꾼 기존 후보는 추천 상세/위치 조회로 현재 좌표를 확인한다. 후보 생성 효과를 보려면 새 구매요청을 생성한다. 기존 구매요청/예약을 초기화하거나 삭제할 필요는 없다. 승인 기한 만료 추가 시연은 기존 결정대로 보류한다.
