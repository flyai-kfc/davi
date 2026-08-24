# Azure Container Apps release 서버

release APK는 `davi-server-release` Container App을 사용한다. 기존 development 서버와 같은
resource group·ACR·Key Vault·Container Apps managed environment·runtime managed identity를
재사용하지만, Container App revision과 인증 경계는 분리한다.

| 앱 빌드 | Container App | 서버 `DAVI_ENV` | 추론 endpoint 인증 |
|---|---|---|---|
| debug | `davi-server-app` | `development` | `X-Davi-Dev-Token` + 선택 세션 JWT |
| release | `davi-server-release` | `production` | 카카오 로그인으로 발급한 세션 JWT |

저장소의 서버 런타임 이름은 호환성을 위해 `development/beta/production`을 유지한다. 여기서
debug/release는 배포 대상 이름이고 release 대상이 가장 엄격한 `production` 프로필을 고른다.
release에는 `DAVI_DEV_ACCESS_TOKEN`과 `DAVI_TEST_ACCESS_TOKEN`을 배선하지 않는다.

## 코드 경계

- `infra/azure/release-server.bicep`: 기존 기반 자원을 참조해 release Container App과 revision만 만든다.
- `.github/workflows/azure-release-deploy.yml`: GitHub-hosted runner에서 image를 build/push하고 정확한
  revision·image·production profile·HTTPS health를 검증한다.
- `app-android/app/build.gradle.kts`: release APK의 고정 HTTPS endpoint와 빈 개발 토큰을 만든다.

development는 `main` 변경 때 자동 배포하지만 release는 수동 승격한다. GitHub Actions의
`Azure release deploy`를 `main`에서 실행해야 하며, `davi-release` environment의 required reviewer를
두면 승인 전에는 Azure가 바뀌지 않는다.

## 1. Azure 관리자: 기존 자원과 권한 확인

먼저 대상 subscription을 고르고 기존 이름을 확인한다.

```bash
az account set --subscription <SUBSCRIPTION_ID>
az group show --name rg-davi-dev --query '{id:id,location:location}' -o json
az acr show --name davidevacrkfc001 --resource-group rg-davi-dev --query loginServer -o tsv
az containerapp env show --name acaenv-davi-dev --resource-group rg-davi-dev --query properties.defaultDomain -o tsv
az keyvault show --name davi-dev-kv-kfc001 --resource-group rg-davi-dev --query name -o tsv
```

managed environment의 default domain이
`greenbeach-0566d3be.koreacentral.azurecontainerapps.io`인지 확인한다. 다르면 release Container App을
만든 뒤 출력된 FQDN으로 `releaseServerUrl`을 갱신해야 한다.

GitHub 배포 identity `id-davi-github-deploy`가 이미 resource group `Contributor`와 ACR `AcrPush`를
갖고 있으면 추가 role assignment는 필요 없다. Container runtime identity `id-davi-server-dev`도
기존 `AcrPull`과 `Key Vault Secrets User`를 그대로 사용한다.

## 2. Key Vault: release 인증 비밀 준비

LLM 키는 같은 공급자를 쓴다면 기존 `GEMINI-API-KEY`를 재사용할 수 있다. 세션 JWT 서명 비밀은
development와 분리해 새 이름으로 만든다. 값은 저장소·GitHub·명령 출력에 남기지 않는다.

```bash
read -s "DAVI_RELEASE_JWT_SECRET?release JWT secret(32자 이상): "
echo
az keyvault secret set \
  --vault-name davi-dev-kv-kfc001 \
  --name DAVI-RELEASE-AUTH-JWT-SECRET \
  --value "$DAVI_RELEASE_JWT_SECRET" \
  --output none
unset DAVI_RELEASE_JWT_SECRET
```

OpenAI를 선택한다면 같은 방식으로 `OPENAI-API-KEY` secret을 준비한다. 배포 identity에는 secret
읽기 권한을 주지 않는다. secret 값은 Container App runtime identity만 읽는다.

## 3. Azure OIDC: release environment subject 추가

기존 deploy identity에 release GitHub environment 전용 federated credential을 하나 더 추가한다.
이 저장소는 immutable owner/repository ID subject를 사용하므로 이름 기반 subject로 바꾸지 않는다.

```bash
az identity federated-credential create \
  --name github-davi-release \
  --identity-name id-davi-github-deploy \
  --resource-group rg-davi-dev \
  --issuer https://token.actions.githubusercontent.com \
  --subject repo:flyai-kfc@308695859/davi@1310845925:environment:davi-release \
  --audiences api://AzureADTokenExchange
```

이미 있으면 `az identity federated-credential show`로 issuer·subject·audience가 정확한지만 확인한다.

## 4. GitHub 관리자: `davi-release` environment

Repository **Settings → Environments → New environment**에서 `davi-release`를 만들고 다음을 설정한다.

1. Deployment branches and tags는 `main`만 허용한다.
2. release 승격자를 required reviewer로 둔다. self-review 허용 여부는 팀 정책에 맞춘다.
3. Environment variables를 등록한다. 값 자체가 비밀인 LLM/JWT 키는 GitHub에 넣지 않는다.

| Variable | 값/출처 |
|---|---|
| `AZURE_CLIENT_ID` | `id-davi-github-deploy`의 clientId |
| `AZURE_TENANT_ID` | `az account show --query tenantId -o tsv` |
| `AZURE_SUBSCRIPTION_ID` | `az account show --query id -o tsv` |
| `DAVI_AZURE_RESOURCE_GROUP` | `rg-davi-dev` |
| `DAVI_AZURE_ACR_NAME` | `davidevacrkfc001` |
| `DAVI_AZURE_KEY_VAULT_NAME` | `davi-dev-kv-kfc001` |
| `DAVI_AZURE_MANAGED_ENVIRONMENT_NAME` | `acaenv-davi-dev` |
| `DAVI_AZURE_CONTAINER_IDENTITY_NAME` | `id-davi-server-dev` |
| `DAVI_AZURE_CONTAINER_APP_NAME` | `davi-server-release` |
| `DAVI_AZURE_AUTH_JWT_SECRET_NAME` | `DAVI-RELEASE-AUTH-JWT-SECRET` |
| `DAVI_AZURE_KAKAO_APP_ID` | 카카오 개발자 콘솔의 숫자 앱 ID |
| `DAVI_AZURE_LLM_PROVIDER` | 선택. 비우면 `gemini`, 또는 `openai` |
| `DAVI_AZURE_OPENAI_KEY_SECRET_NAME` | OpenAI일 때 `OPENAI-API-KEY` |
| `DAVI_AZURE_MODEL` | 선택. 평소 비우고 공급자 기본 모델 사용 |

## 5. 최초 배포와 검증

GitHub **Actions → Azure release deploy → Run workflow**에서 `main`을 선택한다. workflow가 최초
Container App 생성까지 수행하므로 Azure Portal에서 빈 앱을 미리 만들 필요는 없다.

성공 후 아래를 확인한다.

```bash
az containerapp show \
  --name davi-server-release \
  --resource-group rg-davi-dev \
  --query '{fqdn:properties.configuration.ingress.fqdn,revision:properties.latestReadyRevisionName}' \
  -o json

curl --fail --silent --show-error \
  https://davi-server-release.greenbeach-0566d3be.koreacentral.azurecontainerapps.io/health
```

production health 응답은 진단 정보를 노출하지 않고 `{"status":"ok"}`만 반환한다. workflow는 이에
더해 정확한 revision/image, `DAVI_ENV=production`, stub 비활성, 개발 토큰 미배선을 확인한다.
그 다음 서명된 release APK에서 카카오 로그인 → 세션 발급 → next-step 한 회를 실기기로 확인하면
#214의 실제 release 경로가 닫힌다.

로그는 기존 Container Apps environment의 Log Analytics로 들어간다. 서버의 비식별 JSON Lines만
보존하며 goal·화면 label·발화·토큰·키는 기록하지 않는다. 현재 외부 rate limiter는 프로세스
메모리이므로 release도 replica를 1개로 고정한다.
