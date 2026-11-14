# 공공데이터 → 참여 점포 등록

이번 작업은 기존 public_store_records의 선정된 SBIZ 레코드를 stores로 등록하고
matched_store_id를 연결하는 단계다. 상품·일정·사장님 계정 생성은 포함하지 않는다.
새 API·market_code 컬럼·Flyway 변경은 없다. YPAY는 별도 확인 후 기존 매칭 절차로 연결한다.

## 실행 전 직접 준비할 것

- 기존 개발용 DB 접속 설정은 IntelliJ에서 사용자가 직접 관리한다.
- 코드 작성·테스트 과정에서는 .env/IntelliJ 인증정보 파일을 읽거나 복사하지 않는다.
- 이미 존재하는 시연용 OWNER 계정의 users.id를 지정한다. 계정이 없으면 먼저 준비해야 한다.
- SQL로 확인할 때는 SELECT id, role FROM users WHERE role='OWNER'; 만 사용한다.
  비밀번호·해시·이메일 조회는 필요하지 않다.
- 확정한 대상의 SBIZ external_id만 UTF-8 텍스트에 한 줄씩 넣고 .ids 확장자로 저장한다.
  DB 자동 증가 id나 YPAY 원천 목록 번호는 사용하지 않는다.
  #으로 시작하는 설명과 빈 줄은 무시한다. 현재 후보 CSV 전체를 자동 선정하지 않는다.
- 연락처는 수집 데이터에 없으므로 신규 참여 점포는 명시적 허용 시 빈 문자열로 저장한다.
  가짜 전화번호는 생성하지 않는다. FE에서는 빈 연락처를 미등록으로 표시하고 전화 연결을 숨긴다.
  실제 연락처가 필요한 시연이면 이 등록 기능 대신 검증된 연락처를 준비해 등록 방식을 보완한다.

## IntelliJ 실행

기존 인증정보 설정을 사용자가 직접 지정한 Gradle 실행 구성에서 Tasks and arguments에 입력한다.
아래 <직접_입력>과 경로는 실제 값으로 바꾼다. 실행 코드에 비밀번호나 API 키를 넣지 않는다.

사전 점검(기본 dry-run=true):
bootRun --args="--spring.profiles.active=demo-stores --spring.main.web-application-type=none --demo-stores.owner-id=<직접_입력> --demo-stores.external-ids-file=C:/직접지정/selected.ids"

실제 저장(연락처 미등록을 허용하는 데모):
bootRun --args="--spring.profiles.active=demo-stores --spring.main.web-application-type=none --demo-stores.owner-id=<직접_입력> --demo-stores.external-ids-file=C:/직접지정/selected.ids --demo-stores.dry-run=false --demo-stores.allow-missing-phone=true"

public-data 프로필과 함께 실행하지 않는다. 외부 API 키는 필요 없다.
출력: selected, create, connectExisting, alreadyConnected, dryRun.
dry-run=true의 create/connectExisting는 저장 예정 건수다. false이면 처리된 건수다.
dry-run도 일반 Boot 시작의 DB 연결/Flyway는 실행하며, 등록 서비스는 조회/잠금만 수행한다.

## 처리 규칙

- OWNER 존재·역할을 확인할 때 사용자 ID만 조회한다. 인증정보를 조회하지 않는다.
- OWNER 및 선정한 원천 행을 잠그고 원천 ID 순서로 처리한다.
- 같은 원천 행의 기존 연결은 재사용하며 점포 정보·소유자를 덮어쓰지 않는다.
- 기존 이름·주소가 일치하는 참여 점포가 유일하고 같은 OWNER이면 새 생성 없이 연결한다.
- 원천 ID 누락/중복, 여러 참여 점포 후보, 다른 소유자, 선정 목록 내 동일 이름·주소는 중단한다.
- 이름 150자, 업종 100자, 주소 500자 및 필수값·좌표를 사전 검사한다. 잘라 넣지 않는다.
- 미연결 행을 모두 검사한 뒤 생성과 연결을 하나의 트랜잭션으로 처리한다.
  중간 실패 시 이번 호출의 생성·연결은 롤백한다.
- 이미 연결된 원천은 참여 점포의 수정사항을 유지한다. 재실행 시 다시 생성하지 않는다.
- 서로 다른 원천 ID가 실제로 같은 가게인지는 자동 판단하지 않으며 대상 목록을 검토해야 한다.
- 신규 stores.phone은 빈 값이다. 실제 점주용 등록 API의 연락처 필수 정책을 바꾸지 않는다.

## 사용자가 실행 후 확인할 SQL

SELECT id, owner_id, name, address, latitude, longitude,
       CASE WHEN phone='' THEN '미등록' ELSE '등록됨' END AS phone_status
FROM stores ORDER BY id;

SELECT id, external_id, name, matched_store_id
FROM public_store_records
WHERE source='SBIZ' AND matched_store_id IS NOT NULL
ORDER BY id;

동일 목록을 다시 실행했을 때 create=0, connectExisting=0이고
alreadyConnected가 선정 수와 같아야 한다. 실제 저장 검증은 사용자가 실행한다.
등록된 stores.id는 추천·예약용 점포 ID다. SBIZ external_id와 혼동하지 않는다.
원천 정보와 참여 점포의 1:1 상세 대조는 필요 시 별도로 수행한다.

## 검증 범위

DB 없는 Mockito 단위 테스트로 생성·기존 연결·재실행·소유권·잘못된 행의 사전 차단,
연락처 동의, ID 파일 처리와 프로필 충돌 방지를 검증한다.
사용자 인증정보/실제 DB/외부 API를 사용한 검증은 이 작업에서 수행하지 않는다.
## OWNER 계정이 없는 경우

프로젝트 폴더에서 일반 PowerShell로 다음을 사용자가 직접 실행한다.
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\Prepare-DemoOwner.ps1

앱용 이메일·표시 이름·새 비밀번호·비밀번호 확인을 순서대로 입력한다.
비밀번호는 콘솔의 숨김 입력이며 명령행 인자나 파일에 적지 않는다.
도구는 DB에 접속하지 않고 BCrypt 해시가 포함된 INSERT SQL을 클립보드에 준비한다.
Workbench 새 SQL 탭에 붙여 넣고 사용자가 실행하면 OWNER 계정이 생성된다.
실행 결과의 id를 demo-stores.owner-id로 사용한다.
SQL에는 비밀번호 해시가 포함되므로 채팅·Git에 공유하거나 프로젝트에 저장하지 않는다.
사용 후 다른 일반 텍스트를 복사해 클립보드를 바꾼다. 클립보드 기록/동기화를 사용하는 경우
해당 SQL 항목도 직접 삭제한다. 동일 이메일의 기존 계정은 덮어쓰지 않는다.
이 도구는 회원가입 API가 아니며 기존 로그인·응답 계약을 변경하지 않는다.
코드 검증은 -SelfTest로 가상 입력만 사용했다. 실제 비밀번호·계정·DB는 사용하지 않았다.
## 후보 목록 확대 사전 점검

사용자가 제공한 후보 CSV를 기준으로 생성한 파일(아직 최종 상권 확정 목록이 아님):
- demo/bojeong-candidates.ids: 133건
- demo/dongbaek-candidates.ids: 2,045건

원본 보정 135건 중 동일 이름·주소 2건, 동백 2,077건 중 32건은 별도 검토 대상으로 보류했다.
원본 DB에서 삭제하거나, 동일한 실제 가게라고 확정한 것은 아니다.
좌표·필수값·길이·원천 ID 및 후보 목록 간 중복을 오프라인에서 검사했다.
실제 DB의 최신 값·기존 참여 점포·소유권은 다음 dry-run에서 다시 검사한다.
두 .ids 파일은 지리적 상권 확정이나 실제 등록 승인을 의미하지 않는다.
이 단계는 아래 dry-run만 사용하며, 전체 실제 등록은 범위와 보류 행 검토 후 진행한다.

사용자가 확인한 시연 OWNER ID=1 기준:
bootRun --args="--spring.profiles.active=demo-stores --spring.main.web-application-type=none --demo-stores.owner-id=1 --demo-stores.external-ids-file=C:/Together-Market-be/demo/bojeong-candidates.ids"

bootRun --args="--spring.profiles.active=demo-stores --spring.main.web-application-type=none --demo-stores.owner-id=1 --demo-stores.external-ids-file=C:/Together-Market-be/demo/dongbaek-candidates.ids"

기존 IntelliJ 구성의 Tasks and arguments만 사용자가 바꾼다. 인증정보를 복사하거나 새로 읽을 필요가 없다.
expected selected는 각각 133/2045다. create/connectExisting/alreadyConnected의 합이 selected와 같아야 한다.
기존 등록 점포가 있으면 alreadyConnected가 증가한다. create 값을 사전 고정하지 않는다.
## 동백 범위 재검토: 확인된 건물부터 (2026-11-14)

기존 dongbaek-candidates.ids(2,045건)는 넓은 도로명 후보이며 전체 저장에 사용하지 않는다.
공개 자료에서 확인된 다음 건물 주소를 전체 18,498건에 대조했다.
- 썬월드: 동백죽전대로 444 (이마트 공식 점포 안내)
- 문월드: 동백5로 22 (용인시 공개 채용 안내의 사업소 주소)
- 스타월드: 동백중앙로 225-6 (LH 용인권 안내)
원천 행은 총 479건이며 그중 270건은 기존 동백 후보 CSV에서 누락돼 있었다.
현재 영업 중 점포 수를 의미하지 않는다. 공공데이터 갱신 지연·중복 원천 행이 있을 수 있다.

동일 이름·주소 의심 14행은 보류하고 465개 원천 ID를
demo/dongbaek-verified-buildings.ids에 준비했다.
이 파일은 확인된 3개 건물만 포함하며 호수공원 인근 전체의 최종 선정 목록이 아니다.
주변의 다른 건물까지 포함하려면 주소별 검토 목록에서 추가 확인한다.
보정 2행과 기존 동백 32행의 중복 의심 데이터는 16개 그룹으로 정리했으며
대표 ID를 임의 선택하거나 DB의 원천 행을 삭제/통합하지 않았다.

DB 사전 점검만:
bootRun --args="--spring.profiles.active=demo-stores --spring.main.web-application-type=none --demo-stores.owner-id=1 --demo-stores.external-ids-file=C:/Together-Market-be/demo/dongbaek-verified-buildings.ids"

demo/dongbaek-verified-buildings-public.sql은 같은 건물의 SBIZ/YPAY를
DB 원본에서 다시 조회하는 SQL이다. 기존 YPAY 후보에도 빠진 건물 점포가 있을 수 있어
이 조회 결과를 별도 CSV로 내보내어 연결 검토에 사용한다.
인증정보나 DB에 직접 접근해 실행한 작업은 없다.
## 등록 완료 대조

demo/verify-bojeong-registration.sql: 선정된 보정 133개 원천 ID와 실제 연결을 대조한다.
demo/verify-dongbaek-registration.sql: 확인 건물 465개 원천 ID와 실제 연결을 대조한다.
모두 SELECT/CTE만 있으며 DB를 수정하지 않는다. 결과는 한 개의 요약 표다.
정상 조건:
- selected_count = connected_count = distinct_store_count (보정 133, 동백 465)
- unlinked_count = 0
- error_count = 0
오류·미연결이 있으면 각 inspect-*-unresolved.sql에서 원천 ID별 상태를 확인한다.
사용자 OWNER id=1과 비교하며 비밀번호·해시·이메일·DB 접속정보는 조회하지 않는다.
보정 등록 완료는 실제 결과를 받기 전까지 미확인이다.
## 등록 후 중복 검토·와이페이 연결 준비

사용자가 제공한 실행 로그:
- 동백 확인 건물 465건 저장 후 재실행에서 alreadyConnected=465.
- 보정 후보 133건 중 create=132, alreadyConnected=1.
총 598개 원천 행의 연결이 실행 로그에서 확인됐다.
에이전트는 직접 DB나 인증정보에 접근하지 않았다.

demo/export-market-link-review.sql은 현 단계의 동일 주소 범위에서
SBIZ/YPAY 원본과 실제 matched_store_id/store_id를 함께 조회한다.
저장 전 중복 보류 행도 남아 있으므로 이름만 보고 NULL을 일괄 채우지 않는다.
Workbench에서 새 연결로 열고 Don't Limit으로 결과 전체를 CSV(헤더 포함, UTF-8)로 내보낸다.
사용 중인 기존 트랜잭션의 COMMIT/ROLLBACK을 에이전트가 임의로 지시하거나 실행하지 않는다.
CSV 권장 이름: market-link-review.csv.
이 최신 파일을 기준으로 원천 중복 연결 검토와 와이페이 연결 대상을 작성한다.