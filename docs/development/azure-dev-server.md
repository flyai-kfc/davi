# Azure Container Apps 공유 개발 서버

이 문서는 #94의 **개발·도그푸딩 전용** Azure 서버를 최초 준비하고, 이후 `main` 서버 변경을
Azure DevOps Pipeline으로 자동 배포하는 절차다. 제품 배포·사용자 인증·release APK endpoint가
아니다. GitHub Actions는 계속 PR 검증을 담당하고 Azure Pipeline은 PR을 배포하지 않는다.

```text
main의 server/ · infra/azure/ · azure-pipelines.yml 변경
  → Azure DevOps가 <commit SHA>-<build ID> image를 ACR에서 build
  → r<build ID> Container Apps revision 배포
  → readiness 통과 후 HTTPS /health 확인
```

외부 ingress는 팀 debug APK가 다른 네트워크에서도 접근하도록 HTTPS로 열려 있다. `/health` 외의
API는 `X-Davi-Dev-Token`을 요구하고 전역 rate limit을 적용하지만, 이는 제품 사용자 인증이 아니다.
URL과 토큰을 공개 채널·이슈·PR·로그에 붙이지 않는다.

## 코드와 검증 경계

| 경로 | 역할 |
|---|---|
| `server/Dockerfile` | 비루트 `davi` 사용자로 Uvicorn worker 하나를 실행한다. |
| `server/.dockerignore` | `.env`, venv, 테스트, local/raw 상태를 build context에서 제외한다. |
| `infra/azure/dev-foundation.bicep` | ACR, Key Vault, user-assigned identity, Log Analytics, Container Apps Environment와 RBAC를 준비한다. |
| `infra/azure/dev-server.bicep` | Key Vault 참조·HTTPS ingress·단일 replica/revision의 Container App을 배포한다. |
| `azure-pipelines.yml` | `main`의 대상 경로만 image build → revision 배포 → health 검사한다. |
| `.github/workflows/ci.yml` | PR에서 Bicep compile과 Docker build/health, Android HTTPS 설정을 키 없이 검증한다. |

PR CI는 Azure 구독을 변경하지 않는다. 실제 Resource Group 배포와 Android 연결은 아래 체크리스트를
Azure 권한 보유자가 직접 완료해야 #94의 최종 완료 조건을 충족한다.

## 1. Azure 관리자: 기반 리소스 최초 1회 준비

기반 템플릿은 `AcrPull`과 `Key Vault Secrets User` role assignment를 만든다. 실행 계정에는 대상
Resource Group의 **Contributor + User Access Administrator** 또는 Owner가 필요하다.

1. 개발 전용 Resource Group을 만든다. 예: `rg-davi-dev`, Korea Central.
2. `infra/azure/parameters.dev.example.json`을 복사해 gitignore된
   `infra/azure/parameters.dev.json`을 만든다. ACR·Key Vault 이름은 Azure 전체에서 고유해야 한다.
3. 저장소 루트에서 기반 템플릿을 배포한다.

   ```bash
   az deployment group create \
     --resource-group rg-davi-dev \
     --name davi-dev-foundation \
     --template-file infra/azure/dev-foundation.bicep \
     --parameters @infra/azure/parameters.dev.json
   ```

4. Key Vault Portal의 **Secrets**에서 다음 값을 직접 만든다. 명령행 인자·Pipeline 변수·Git에
   실제 값을 넣지 않는다.

   | Secret name | 값 |
   |---|---|
   | `GEMINI-API-KEY` | 서버 전용 Gemini API 키 |
   | `DAVI-DEV-ACCESS-TOKEN` | 32자 이상의 별도 개발 토큰 |

Container App은 versionless Key Vault URI를 참조한다. secret 갱신은 Container Apps가 동기화하지만
팀 debug APK의 개발 토큰도 함께 교체해야 한다. Key Vault purge protection과 90일 soft delete 때문에
vault 삭제 후 같은 이름을 즉시 재사용할 수 없다는 점도 고려한다.

## 2. Azure DevOps 관리자: main Pipeline 연결

1. Azure DevOps에서 GitHub `flyai-kfc/davi`를 소스로 Pipeline을 만든다.
2. Azure Resource Manager Service Connection을 만든다.
   - 권장 이름: `davi-dev-azure-rm`
   - 범위: 개발 Resource Group 하나
   - 인증: Workload Identity Federation
3. YAML 경로를 `/azure-pipelines.yml`로 지정한다.
4. 다음 Pipeline variable을 등록한다. 모두 리소스 식별자이며 secret 값이 아니다.

   | Variable | 예시 |
   |---|---|
   | `DAVI_AZURE_SERVICE_CONNECTION` | `davi-dev-azure-rm` |
   | `DAVI_AZURE_RESOURCE_GROUP` | `rg-davi-dev` |
   | `DAVI_AZURE_ACR_NAME` | `davidevacr...` |
   | `DAVI_AZURE_KEY_VAULT_NAME` | `davi-dev-kv-...` |
   | `DAVI_AZURE_MANAGED_ENVIRONMENT_NAME` | `acaenv-davi-dev` |
   | `DAVI_AZURE_CONTAINER_IDENTITY_NAME` | `id-davi-server-dev` |
   | `DAVI_AZURE_CONTAINER_APP_NAME` | `davi-server-dev` |

`GEMINI_API_KEY`와 `DAVI_DEV_ACCESS_TOKEN`은 Pipeline variable로 만들지 않는다. Pipeline과 Service
Connection은 Key Vault secret 값을 읽지 않으며 Container App identity만 runtime에 읽는다.

Pipeline은 `main` push 중 `server/**`, `infra/azure/**`, `azure-pipelines.yml` 변경에만 실행되고
`pr: none`으로 PR 배포를 금지한다. 같은 commit을 재실행해도 build ID가 image tag와 revision suffix에
포함되므로 기존 tag를 덮어쓰거나 배포가 생략되지 않는다.

## 3. 첫 배포와 Android 실기기 확인

기반 리소스와 secret, Pipeline 변수를 준비한 뒤 Pipeline을 수동으로 한 번 실행한다. 성공한 health
단계는 다음 형식의 URL만 출력하며 응답 본문이나 token은 출력하지 않는다.

```text
shared development URL: https://davi-server-dev.<hash>.<region>.azurecontainerapps.io
```

각 앱 개발자는 추적되지 않는 `app-android/local.properties`에 다음을 넣고 debug APK를 다시 만든다.

```properties
DAVI_DEBUG_SERVER_URL=https://davi-server-dev.<hash>.<region>.azurecontainerapps.io
DAVI_DEBUG_ACCESS_TOKEN=<Key Vault의 DAVI-DEV-ACCESS-TOKEN과 같은 값>
```

Android debug는 기본 443의 `*.azurecontainerapps.io`만 HTTPS 원격 주소로 허용한다. HTTPS에서는
Network Security Config에 평문 예외가 생기지 않는다. release는 개발 URL과 토큰이 있으면 계속
빌드를 거부한다.

최초 배포의 완료 체크:

- Azure deployment와 Pipeline build/deploy/health 단계가 모두 성공한다.
- Container App image tag가 `<main commit SHA>-<Pipeline build ID>`다.
- active revision suffix가 `r<Pipeline build ID>`다.
- Container App 설정에는 Key Vault reference만 있고 secret 평문이 없다.
- `/health`는 HTTPS 200이며 `development`, `stub_llm=false`다.
- 토큰 없는 `/v1/next-step`은 401이고 유효 토큰을 쓴 익명 fixture 요청은 정상 응답한다.
- Android debug 실기기에서 같은 endpoint로 목표 정리와 다음 안내를 각각 한 번 확인한다.
- 앱·서버 로그에 URL 외 token, 목표, 화면 label, 발화, 모델 원문이 없는지 확인한다.

## 운영·장애·롤백

- Container App과 Uvicorn worker는 각각 1개다. 현재 전역 rate limit과 인메모리 상태를 replica 간에
  나누지 않기 위한 개발 환경 제약이다.
- `DAVI_DOGFOOD_DEVICE_IDS`는 빈 값으로 고정한다. Azure 임시 파일 시스템에 raw 화면 트리를
  저장하지 않는다. fixture 원본 수집은 승인된 로컬 공기계 경로만 사용한다.
- 비식별 JSON Lines는 stderr→Log Analytics로 전달되며 30일 보존한다. 정상 `/health`, IP/port,
  User-Agent, 요청 본문과 secret은 애플리케이션 로그에 남기지 않는다.
- 배포 실패는 Azure DevOps의 Bicep deployment, Container App revision 상태, health 단계 순서로 본다.
  Gemini 요청만 실패하면 비식별 request ID와 Logical Request ID로 대조한다.
- 롤백은 문제 commit을 `main`에서 revert해 새 build ID의 image/revision으로 다시 배포한다. 기존 ACR
  tag를 덮어쓰거나 닫힌 #95 브랜치를 재사용하지 않는다.
