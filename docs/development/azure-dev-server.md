# Azure Container Apps 공유 개발 서버

이 문서는 #94의 **개발·도그푸딩 전용** Azure 서버를 최초 준비하고, 이후 `main` 서버 변경을
GitHub Actions로 자동 배포하는 절차다. 제품 배포·사용자 인증·release APK endpoint가 아니다.
PR 검증과 배포는 서로 다른 workflow이며, 배포 workflow는 PR에서 실행되지 않는다.

```text
main의 server/ · infra/azure/ · azure-dev-deploy.yml 변경
  → GitHub-hosted runner가 고정 base image와 Docker 전용 lock으로 linux/amd64 image를 build해 ACR에 push
  → r<run ID>-<attempt> Container Apps revision 배포
  → 배포 output의 정확한 revision/image가 active·healthy·traffic 100%인지 대조
  → 같은 Container App HTTPS /health 확인
```

외부 ingress는 팀 debug APK가 다른 네트워크에서도 접근하도록 HTTPS로 열려 있다. `/health` 외의
API는 `X-Davi-Dev-Token`을 요구하고 전역 rate limit을 적용하지만, 이는 제품 사용자 인증이 아니다.
URL과 토큰을 공개 채널·이슈·PR·로그에 붙이지 않는다.

## 코드와 검증 경계

| 경로 | 역할 |
|---|---|
| `server/Dockerfile` | digest로 고정한 Python base와 `requirements.lock.txt`를 써 비루트 `davi` 사용자로 Uvicorn worker 하나를 실행한다. |
| `server/.dockerignore` | `.env`, venv, 테스트, local/raw 상태를 build context에서 제외한다. |
| `infra/azure/dev-foundation.bicep` | ACR, Key Vault, user-assigned identity, Log Analytics, Container Apps Environment와 RBAC를 준비한다. |
| `infra/azure/dev-server.bicep` | Key Vault 참조·HTTPS ingress·단일 replica/revision의 Container App을 배포한다. |
| `.github/workflows/azure-dev-deploy.yml` | `main`의 대상 경로만 OIDC 로그인 → hosted Docker build → ACR push → Bicep revision 배포 → health 검사한다. |
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

4. Key Vault Portal의 **Secrets**에서 다음 값을 직접 만든다. 명령행 인자·GitHub 변수·Git에
   실제 값을 넣지 않는다.

   Secret을 만드는 사람에게는 vault 범위의 `Key Vault Secrets Officer`가 필요하다. 기반 Bicep은
   runtime identity에 읽기 권한만 부여하며 특정 팀원 계정을 템플릿에 고정하지 않는다.

   | Secret name | 값 | 필수 |
   |---|---|---|
   | `GEMINI-API-KEY` | 서버 전용 Gemini API 키 | 항상 |
   | `DAVI-DEV-ACCESS-TOKEN` | 32자 이상의 별도 개발 토큰 | 항상 |
   | `DAVI-TEST-ACCESS-TOKEN` | 32자 이상의 외부 테스트 참가자 토큰 | **외부 테스트 기간에만** |

   마지막 행은 **평소에는 만들지 않는다.** `dev-server.bicep`의 `testAccessTokenSecretName`
   기본값이 비어 있어서, 파라미터를 넘기지 않으면 Container App이 이 secret을 참조하지 않는다.
   외부 테스트를 열 때 **secret을 먼저 만들고** 배포에 파라미터를 넘긴다:

   ```bash
   az deployment group create ... --parameters testAccessTokenSecretName=DAVI-TEST-ACCESS-TOKEN
   ```

   순서를 거꾸로 하면 — 즉 secret 없이 파라미터를 넘기면 — Container Apps가 revision을 만들 때
   `keyVaultUrl`을 해석하지 못해 새 revision이 Healthy에 도달하지 못하고, 배포 워크플로의
   revision·health 검증이 실패한다. 그때는 공용 개발 서버가 이전 revision에 묶이고
   **이후 배포까지 함께 막힌다.** 테스트가 끝나면 파라미터를 빼고 secret을 지우면 폐기가 끝난다
   (팀 개발 토큰은 그대로 살아 있다).

Container App은 versionless Key Vault URI를 참조한다. secret 갱신은 Container Apps가 동기화하지만
팀 debug APK의 개발 토큰도 함께 교체해야 한다. 새 dev vault는 purge protection을 켜지 않고 Azure가
허용하는 최소값인 7일 soft delete를 쓴다. 삭제 뒤 7일 안에 같은 이름을 다시 써야 한다면 권한 있는
관리자가 삭제된 vault를 명시적으로 purge해야 한다.

이미 만들어진 vault의 soft-delete 기간은 바꿀 수 없고, 한번 켠 purge protection도 끌 수 없다.
따라서 기존 `davi-dev-kv-kfc001`의 90일·purge-protected 상태는 이 템플릿 수정으로 소급 변경되지
않는다. 기존 vault를 새 정책으로 옮기려면 새 이름의 vault 생성, 두 secret의 수동 재등록, GitHub
environment 변수 변경을 별도 작업으로 검토한다. 이 PR은 비밀값을 읽거나 복사하지 않는다.

## 2. Azure 관리자: GitHub 배포 identity 준비

배포 identity는 Container App이 런타임에 쓰는 `id-davi-server-dev`와 분리한다. 아래 예시는
`id-davi-github-deploy` user-assigned managed identity를 만들고 GitHub environment에만 신뢰를 준다.
실행 계정에는 identity 생성과 role assignment를 위한 대상 Resource Group의 Owner 또는
User Access Administrator 권한이 필요하다.

1. GitHub Actions 전용 identity를 만든다. `id-davi-github-deploy`가 이미 있으면 새로 만들지 않고
   다음 단계에서 client/principal ID를 확인한다.

   ```bash
   az identity create \
     --name id-davi-github-deploy \
     --resource-group rg-davi-dev
   ```

2. 배포 identity와 대상 scope를 확인한다. 아래 값은 비밀값이 아닌 Azure 리소스 식별자다.

   ```bash
   DAVI_DEPLOY_CLIENT_ID="$(az identity show \
     --name id-davi-github-deploy \
     --resource-group rg-davi-dev \
     --query clientId -o tsv)"
   DAVI_DEPLOY_PRINCIPAL_ID="$(az identity show \
     --name id-davi-github-deploy \
     --resource-group rg-davi-dev \
     --query principalId -o tsv)"
   DAVI_RESOURCE_GROUP_ID="$(az group show \
     --name rg-davi-dev \
     --query id -o tsv)"
   DAVI_ACR_ID="$(az acr show \
     --name <ACR_NAME> \
     --resource-group rg-davi-dev \
     --query id -o tsv)"
   ```

3. identity에는 Resource Group 배포 권한과 ACR image push 권한만 준다. Key Vault secret 값 읽기
   권한은 주지 않는다.

   ```bash
   az role assignment create \
     --assignee-object-id "$DAVI_DEPLOY_PRINCIPAL_ID" \
     --assignee-principal-type ServicePrincipal \
     --role Contributor \
     --scope "$DAVI_RESOURCE_GROUP_ID"

   az role assignment create \
     --assignee-object-id "$DAVI_DEPLOY_PRINCIPAL_ID" \
     --assignee-principal-type ServicePrincipal \
     --role AcrPush \
     --scope "$DAVI_ACR_ID"
   ```

4. GitHub의 `davi-development` environment에서 발급된 OIDC token만 허용하는 federated credential을
   추가한다. organization·repository·environment 이름은 대소문자까지 실제 값과 일치해야 한다.

   ```bash
   az identity federated-credential create \
     --name github-davi-development \
     --identity-name id-davi-github-deploy \
     --resource-group rg-davi-dev \
     --issuer https://token.actions.githubusercontent.com \
     --subject repo:flyai-kfc@308695859/davi@1310845925:environment:davi-development \
     --audiences api://AzureADTokenExchange
   ```

   이 저장소는 2026-07-24에 생성돼 GitHub의 2026-07-15 이후 신규 저장소 기본 정책에 따라
   owner/repository ID가 포함된 immutable subject를 사용한다. 2026-08-10 확인한 GitHub
   `sub_claim_prefix`와 위 subject가 일치한다. GitHub OIDC subject 설정을 나중에 바꾸면 federated
   credential도 GitHub가 반환하는 새 prefix에 맞춰 함께 갱신한다.

## 3. GitHub 관리자: environment와 변수 연결

1. 저장소 **Settings → Environments → New environment**에서 `davi-development`를 만든다.
2. **Deployment branches and tags**를 `Selected branches and tags`로 바꾸고 `main`만 추가한다.
   완전 자동배포가 목표라면 required reviewer는 두지 않는다. 승인 후 배포가 필요하면 reviewer를
   추가할 수 있지만 모든 `main` 배포가 대기 상태가 된다.
3. `davi-development`의 **Environment variables**에 다음을 등록한다. client secret과 GitHub secret은
   만들지 않는다.

   | Variable | 예시/출처 |
   |---|---|
   | `AZURE_CLIENT_ID` | 위 `DAVI_DEPLOY_CLIENT_ID` |
   | `AZURE_TENANT_ID` | `az account show --query tenantId -o tsv` |
   | `AZURE_SUBSCRIPTION_ID` | `az account show --query id -o tsv` |
   | `DAVI_AZURE_RESOURCE_GROUP` | `rg-davi-dev` |
   | `DAVI_AZURE_ACR_NAME` | `davidevacrkfc001` |
   | `DAVI_AZURE_KEY_VAULT_NAME` | `davi-dev-kv-kfc001` |
   | `DAVI_AZURE_MANAGED_ENVIRONMENT_NAME` | `acaenv-davi-dev` |
   | `DAVI_AZURE_CONTAINER_IDENTITY_NAME` | `id-davi-server-dev` |
   | `DAVI_AZURE_CONTAINER_APP_NAME` | `davi-server-app` |

4. organization의 Actions 사용 정책이 제한돼 있다면 `actions/checkout@v4`와 `azure/login@v2`를
   허용한다. workflow의 `id-token: write`는 OIDC token 발급에만 쓰며 repository contents 쓰기
   권한은 주지 않는다.
5. `main` 보호 규칙에서 기존 CI를 필수 검사로 유지한다. 배포 workflow는 PR에서 실행되지 않고,
   병합된 코드만 공유 서버에 반영한다.

`GEMINI_API_KEY`와 `DAVI_DEV_ACCESS_TOKEN`은 GitHub 변수나 secret으로 만들지 않는다. workflow의
배포 identity는 Key Vault secret 값을 읽지 않으며 Container App runtime identity만 읽는다.

workflow는 `main` push 중 `server/**`, `infra/azure/**`, 자신의 YAML 변경에만 실행된다. 수동 실행도
`main` ref만 허용한다. 같은 commit을 재실행해도 run ID와 attempt가 image tag와 revision suffix에
포함되므로 기존 tag를 덮어쓰거나 배포가 생략되지 않는다. 고정 concurrency group으로 여러 배포를
직렬화해 이전 실행과 새 실행의 image/revision이 엇갈리지 않게 한다.

이미지는 GitHub-hosted Ubuntu runner의 Docker daemon에서 `linux/amd64`로 build한 뒤 `az acr login`으로
얻은 OIDC identity 자격으로 push한다. `az acr build`/ACR Tasks와 `az containerapp up`은 사용하지 않는다.
Azure for Students처럼 ACR Tasks가 허용되지 않는 구독에서도 같은 workflow를 쓰고, API 키나 개발
token은 build context·Docker 명령·GitHub 변수에 전달하지 않는다.

Docker build만 `server/requirements.lock.txt`를 사용한다. 로컬 개발·테스트는 계속
`requirements-dev.txt`가 참조하는 하한 기반 `requirements.txt`를 사용한다. 런타임 의존성을 바꿀 때는
Linux amd64의 `python:3.12-slim-bookworm`에서 `requirements.txt`를 설치한 `pip freeze` 결과로 lock을
갱신하고, Python base digest도 의도적으로 함께 검토한다.

## 4. 첫 배포와 Android 실기기 확인

기반 리소스와 secret, federated credential, GitHub environment 변수를 준비한 뒤 `main` 병합으로
발생한 첫 자동 실행을 확인한다.
필요해 수동 재실행할 때도 branch는 `main`만 선택한다. 성공한 검증 단계는 Bicep 배포 output과
Container Apps가 보고한 revision 이름·container image를 직접 비교한 뒤 다음 형식의 비민감 식별자와
URL만 출력한다. `/health` 응답 본문이나 token은 출력하지 않는다.

```text
verified revision: davi-server-app--r<run ID>-<attempt>
verified image: <registry>/davi-server:<main commit SHA>-<run ID>-<attempt>
shared development URL: https://davi-server-app.<hash>.<region>.azurecontainerapps.io
```

각 앱 개발자는 추적되지 않는 `app-android/local.properties`에 다음을 넣고 debug APK를 다시 만든다.

```properties
DAVI_DEBUG_SERVER_URL=https://davi-server-app.<hash>.<region>.azurecontainerapps.io
DAVI_DEBUG_ACCESS_TOKEN=<Key Vault의 DAVI-DEV-ACCESS-TOKEN과 같은 값>
```

Android debug는 기본 443의 `*.azurecontainerapps.io`만 HTTPS 원격 주소로 허용한다. HTTPS에서는
Network Security Config에 평문 예외가 생기지 않는다. release는 개발 URL과 토큰이 있으면 계속
빌드를 거부한다.

최초 배포의 완료 체크:

- GitHub Actions의 login/build/deploy/revision+health 단계가 모두 성공한다.
- 검증된 Container App image tag가 `<main commit SHA>-<GitHub run ID>-<attempt>`다.
- 검증된 active·healthy revision suffix가 `r<GitHub run ID>-<attempt>`이고 traffic이 100%다.
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
- 배포 실패는 GitHub Actions의 OIDC login, Docker build/ACR push, Bicep deployment, 이번 배포의 정확한
  revision/image 상태, health 단계 순서로 본다. 이전 revision의 정상 응답은 성공으로 처리하지 않는다.
  Gemini 요청만 실패하면 비식별 request ID와 Logical Request ID로 대조한다.
- 롤백은 문제 commit을 `main`에서 revert해 새 Actions run ID의 image/revision으로 다시 배포한다. 기존 ACR
  tag를 덮어쓰거나 닫힌 #95 브랜치를 재사용하지 않는다.
