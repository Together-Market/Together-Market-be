# GitHub Actions CI/CD 준비

기존 `.github/workflows/ci.yml`은 PR과 `dev`/`main` 푸시에서 검사한다. `deploy.yml`은 `main` 푸시의 CI 전체 성공 후 동일 커밋을 빌드해 Artifact Registry에 올리고, 기존 Cloud Run 서비스의 이미지만 새 버전으로 배포한다. `main`에서 수동 실행도 가능하다. 기존 Cloud Run 환경변수, Secret Manager 참조, 실행 서비스 계정은 배포 명령에서 다시 지정하지 않는다.

## 최초 1회 Google Cloud 설정

1. GitHub Actions용 **별도 배포 서비스 계정**을 만든다. 기존 `together-market-runtime`은 실행 전용이므로 배포 권한을 주지 않는다.
2. 배포 서비스 계정에 `together-market-images` 저장소의 Artifact Registry Writer, 기존 Cloud Run 서비스의 Cloud Run Developer, 실행 서비스 계정 `together-market-runtime`의 Service Account User 권한을 부여한다. 이 범위 외 프로젝트 전체 관리자 권한은 필요 없다.
3. Google Cloud IAM의 Workload Identity Pool/Provider에서 GitHub OIDC를 신뢰하도록 연결한다. 허용 대상을 이 저장소 `Together-Market/Together-Market-be`의 `main` 배포 작업으로 제한한다. 배포 서비스 계정에는 해당 주체의 Workload Identity User를 부여한다. JSON 키 파일은 만들지 않는다.
4. GitHub 저장소의 **Settings → Secrets and variables → Actions → Variables**에 비밀값이 아닌 `GCP_WIF_PROVIDER`(provider의 전체 리소스 이름), `GCP_DEPLOY_SERVICE_ACCOUNT`(배포 서비스 계정 이메일)를 등록한다. 값이 없으면 배포 job은 실행되지 않는다.
5. 이미지 업로드 기능도 사용할 경우 `docs/product-image-upload.md`에 따라 별도 비공개 버킷, 런타임 IAM, Cloud Run의 `PRODUCT_IMAGE_BUCKET` 및 `PRODUCT_IMAGE_PUBLIC_BASE_URL`을 먼저 설정한다.

설정 후 `main` 푸시 CI가 성공하면 Actions의 `Deploy Cloud Run`에서 실행 결과를 확인한다. 최초에는 `workflow_dispatch`로 `main`에서 한 번 실행해 권한과 환경변수 보존을 확인하는 것이 좋다. PR 검사만으로는 배포하지 않는다. 사용자 승인 없이 에이전트가 커밋, 푸시, 병합하거나 실제 Cloud Run 서비스를 변경하지 않는다.
