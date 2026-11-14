# 배포 준비 최종 보안 점검

2026-11-15, `chore/37-cloud-run-deployment`의 `4b032ab` 기준 로컬 점검. 커밋·푸시·배포·실제 DB 작업은 수행하지 않았다. 이 문서는 정적 점검과 로컬 테스트의 결과이며 운영 환경 침투 테스트나 전체 의존성 취약점 분석 결과는 아니다.

## 확인 결과

- Git이 추적하는 텍스트 파일 336개를 검사했다. 개인 키 헤더, 서비스 계정 JSON 형태, 대표적인 GitHub/OpenAI/Google API 키 형태, URL 안의 비밀번호, 주요 비밀 환경변수의 직접 대입 패턴 후보는 0건이다. 값 자체는 출력하지 않았다. `.env`와 개인 IDE 설정은 열지 않았다. 이 제한된 패턴 검사로 모든 비밀값의 부재를 증명할 수는 없다.
- 실행 JAR 항목 이름 611개에서 `.env`, `.idea`, `.aws`, `.ssh`, 개인 키 확장자 및 DB 덤프 이름 일치 0건이다. `.dockerignore`와 `.gcloudignore`는 실행 JAR과 이미지 빌드 파일만 업로드 대상으로 지정한다. 실행 JAR 내용의 모든 문자열을 검사한 것은 아니다.
- 보안 설정에서 기본적으로 인증을 요구하고 `/owner/**`에는 OWNER 권한을 요구한다. 세션 쿠키 전용 전달, 로그인 시 세션 ID·CSRF 회전, POST/PUT/PATCH의 CSRF 검사, 정확한 CORS 허용 출처 목록을 확인했다. 소유 점포 확인은 서비스에서도 실행한다.
- Kakao·Gemini·OpenAI 호출은 고정 HTTPS 주소와 시간 제한을 사용한다. Kakao의 키·외부 오류 본문·예외 원문은 API 응답에 포함하지 않는다. 추천 API의 외부 응답 본문도 오류로 전달하지 않는다. 카카오 조회 결과의 영구 저장은 차단되어 있다.
- `cloudsql` 프로필에서만 JDBC 세션을 활성화한다. 세션 쿠키는 HttpOnly, 기본 Secure=true, SameSite=None이다. 데이터베이스 연결은 런타임 환경변수와 Cloud Run 서비스 계정으로 설정한다. 자동 카탈로그 입력은 기본 OFF다.
- Java 21에서 `spotlessCheck build --offline --no-daemon` 성공. JUnit 보고서 총 390개 중 336개 통과, 실제 DB 등 별도 환경이 필요한 54개 생략, 실패·오류 0개. `git diff --check` 통과. 테스트 실행 시 DB·API 키 관련 환경변수를 해당 프로세스에서 제거했다.

이전 문서의 오래된 메모리 세션 설명은 로컬 `demo`와 배포용 `cloudsql`을 구분하도록 수정했다. 오래된 작업 기록인 `predeployment-review.md`에는 이 문서로 연결하는 안내를 추가했다.

## 배포 전 남은 보안 확인

1. **공개 API 요청 제한:** 현재 로그인과 점주 주소 미리보기 API에는 계정/IP별 요청 횟수 제한이 없다. 공개 배포 전 적용 방법을 정하고 반복 로그인 실패 및 주소 조회 남용을 시험해야 한다. Kakao 쿼터는 로그인 공격 방어 수단이 아니다.
2. **실제 환경 검증:** Cloud SQL에서 V5 마이그레이션과 전용 DB 계정 권한을 확인한다. 실행 전 원본 DB를 백업한다. 프런트 `http://localhost:5173`에서 HTTPS 백엔드로 로그인·CSRF·쿠키·로그아웃·재배포 후 세션 유지를 실제 브라우저에서 시험한다. 서드파티 쿠키 정책 때문에 Postman 통과만으로 완료할 수 없다.
3. **CI와 의존성:** PR의 MySQL 통합 테스트와 필수 검사 결과를 확인한다. Spring Security·Spring Data JPA·Tomcat의 공개 공식 공지를 현재 사용 버전과 제한적으로 대조했지만 전체 전이 의존성 SCA와 컨테이너 이미지 CVE 검사는 실행하지 않았다. 배포 이미지가 정해지면 이미지 스캔 결과를 확인한다.
4. **클라우드 접근:** Cloud SQL을 `0.0.0.0/0` 승인 네트워크로 열지 않고, 전용 실행 서비스 계정에 필요한 역할만 부여한다. DB 비밀번호와 API 키는 사용자 본인이 Secret Manager에 직접 넣는다. 서비스 설정·DB 덤프·로그인 쿠키·CSRF 값은 채팅이나 PR에 공유하지 않는다.

위 항목이 끝나기 전에는 실제 서비스의 보안 검증 완료로 표시하지 않는다.

## 참고한 공식 공지

- [Spring 보안 공지](https://spring.io/security/)
- [Spring Data JPA CVE-2026-47834](https://spring.io/security/cve-2026-47834/)
- [Apache Tomcat 11 보안 공지](https://tomcat.apache.org/security-11.html)
