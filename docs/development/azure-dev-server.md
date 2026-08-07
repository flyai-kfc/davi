# Azure Container Apps 공유 개발 서버

이 문서는 **개발·도그푸딩 전용** Azure 서버를 한 번 준비하고, 이후 `main`의 서버 변경을
Azure DevOps Pipeline으로 자동 배포하는 절차다(#94). Android/서버 PR CI는 계속 GitHub Actions가
담당하며, Azure Pipeline은 PR에서 실행·배포하지 않는다.

```text
server/ · infra/azure/ · azure-pipelines.yml 변경이 main에 병합
  → Azure DevOps가 ACR에서 이미지 빌드
  → Azure Container Apps의 단일 revision 갱신
  → HTTPS /health 확인
```

이 서버는 제품 배포나 사용자 인증 서비스가 아니다. 외부 HTTPS ingress는 팀 debug APK의 접근을
위한 것이며, 앱의 `DAVI_DEBUG_ACCESS_TOKEN`을 반드시 요구한다. Gemini 키는 서버만 알고 APK·Git·
Pipeline YAML에는 넣지 않는다.

## 코드에 포함된 것

| 경로 | 역할 |
|---|---|
| `server/Dockerfile` | FastAPI 실행 이미지. `.env`·도그푸딩 raw·테스트는 이미지에 넣지 않는다. |
| `infra/azure/dev-foundation.bicep` | ACR, Key Vault, Managed Identity, Log Analytics, Container Apps Environment와 최소 RBAC를 한 번 준비한다. |
| `infra/azure/dev-server.bicep` | 기존 기반 리소스에 `davi-server-dev` revision을 적용한다. Key Vault 비밀값은 참조만 한다. |
| `azure-pipelines.yml` | `main`의 서버/인프라 변경에서만 이미지 빌드·배포·health 확인을 수행한다. |

Container App은 `minReplicas: 1`, `maxReplicas: 1`이다. scale-to-zero로 인한 첫 요청 지연과
동시 Gemini 호출 증가를 피하기 위한 개발 환경 선택이며, #82의 timeout/dedupe 문제를 대체하지 않는다.

## 1. Azure 관리자: 기반 리소스를 한 번 준비

이 단계는 Managed Identity에 `AcrPull`·`Key Vault Secrets User` 역할을 부여한다. 따라서 실행 계정에는
대상 Resource Group의 **Contributor와 User Access Administrator**(또는 Owner)가 필요하다. 이후
Pipeline에는 Resource Group Contributor만 주면 된다.

1. Azure Portal 또는 Cloud Shell에서 개발 전용 Resource Group을 만든다. 예: `rg-davi-dev`, Korea Central.
2. `infra/azure/parameters.dev.example.json`을 복사해 **추적하지 않는**
   `infra/azure/parameters.dev.json`을 만든다. ACR과 Key Vault 이름은 Azure 전체에서 고유해야 한다.
3. 저장소 루트에서 기반 리소스를 배포한다.

   ```bash
   az deployment group create \
     --resource-group rg-davi-dev \
     --name davi-dev-foundation \
     --template-file infra/azure/dev-foundation.bicep \
     --parameters @infra/azure/parameters.dev.json
   ```

4. Key Vault의 **Secrets** 화면에서 다음 두 값을 직접 만든다. 터미널 인자·Pipeline 변수·Git에 붙이지 않는다.

   | Secret name | 값 |
   |---|---|
   | `GEMINI-API-KEY` | 현재 서버가 쓰는 Gemini API 키 |
   | `DAVI-DEV-ACCESS-TOKEN` | 32자 이상의 임의 개발 토큰 |

   Key Vault RBAC를 쓴다. 값을 넣는 사람에게 `Key Vault Secrets Officer` 이상의 역할이 필요하다.
   `DAVI-DEV-ACCESS-TOKEN`은 기존 LAN 서버와 별도 값으로 새로 만들어도 된다.

5. Key Vault purge protection은 켜져 있다. 실수로 secret을 지우는 것은 막지만, 리소스를 폐기해도
   vault 이름과 삭제본은 보존 기간 동안 즉시 재사용할 수 없다. 개발 리소스 이름을 정할 때 고려한다.

Azure 기본 리소스가 만들기 전에는 Pipeline을 실행하지 않는다. 기반 템플릿은 반복 적용해도 같은 이름의
리소스를 갱신하는 선언형 구성이다.

## 2. Azure DevOps 관리자: GitHub main 배포 Pipeline 연결

1. Azure DevOps에서 프로젝트를 만들고 GitHub의 `flyai-kfc/davi`를 Pipeline 저장소로 연결한다.
2. **Project settings → Service connections**에서 Azure Resource Manager 연결을 만든다.
   - 이름: `davi-dev-azure-rm` (다른 이름도 가능)
   - 범위: `rg-davi-dev` Resource Group만
   - 인증: **Workload Identity Federation**
   - 서비스 연결을 만드는 계정에는 Azure DevOps Service Connection 관리 권한과 Azure 구독 권한이 필요하다.
3. Pipeline YAML 경로를 저장소 루트의 `azure-pipelines.yml`로 지정한다.
4. Pipeline Variables에 아래 이름을 등록한다. 모두 **비밀값이 아닌 리소스 식별자**다.

   | Variable | 예시 |
   |---|---|
   | `DAVI_AZURE_SERVICE_CONNECTION` | `davi-dev-azure-rm` |
   | `DAVI_AZURE_RESOURCE_GROUP` | `rg-davi-dev` |
   | `DAVI_AZURE_ACR_NAME` | `davidevacr...` |
   | `DAVI_AZURE_KEY_VAULT_NAME` | `davi-dev-kv-...` |
   | `DAVI_AZURE_MANAGED_ENVIRONMENT_NAME` | `acaenv-davi-dev` |
   | `DAVI_AZURE_CONTAINER_IDENTITY_NAME` | `id-davi-server-dev` |
   | `DAVI_AZURE_CONTAINER_APP_NAME` | `davi-server-dev` |

`GEMINI_API_KEY`, `DAVI_DEV_ACCESS_TOKEN`은 이 화면에 등록하지 않는다. Pipeline은 Key Vault 값을
읽지 않으며 Container App의 Managed Identity만 runtime에 읽는다.

## 3. 첫 배포와 팀 전달

기반 리소스·Key Vault·Pipeline 변수가 준비된 뒤 `main`에 서버 또는 인프라 변경을 병합하거나,
Azure DevOps에서 Pipeline을 수동 실행한다. 성공 로그 마지막에는 다음 형식의 URL이 나온다.

```text
shared development URL: https://davi-server-dev.<hash>.<region>.azurecontainerapps.io
```

각 앱 개발자는 Git에 올리지 않는 `app-android/local.properties`에 아래를 넣고 debug APK를 다시
빌드한다.

```properties
DAVI_DEBUG_SERVER_URL=https://davi-server-dev.<hash>.<region>.azurecontainerapps.io
DAVI_DEBUG_ACCESS_TOKEN=<DAVI-DEV-ACCESS-TOKEN과 같은 값>
```

URL과 토큰은 팀의 비공개 채널로만 전달한다. Gemini 키·Azure Portal/DevOps 권한은 전달하지 않는다.
토큰을 교체하면 Key Vault 값을 바꾼 뒤 새 revision을 배포하고, 팀원도 값을 바꿔 debug APK를 다시
빌드해야 한다.

## 운영 경계와 장애 확인

- PR은 Azure Pipeline의 `pr: none` 때문에 배포하지 않는다. GitHub Actions의 PR CI는 그대로다.
- `server/**`, `infra/azure/**`, `azure-pipelines.yml` 변경이 `main`에 들어갈 때만 배포한다.
- `/health`는 배포 확인용이며 토큰을 요구하지 않는다. 모델 호출은 하지 않는다.
- 실제 `/v1/next-step`과 `/v1/intent/slot-fill`은 `X-Davi-Dev-Token`이 없거나 틀리면 401이다.
- 운영 로그는 기존 앱의 비식별 JSON 이벤트만 Console/Log Analytics에 보낸다. 목표·화면 label·발화·
  대화 이력·요청 본문·Gemini 원문·IP/port·token은 앱 로그에 넣지 않는다.
- Azure 서버에서는 `DAVI_DOGFOOD_DEVICE_IDS`를 빈 값으로 고정한다. 원본 화면 트리를
  cloud의 임시 파일 시스템에 저장하지 않는다. raw fixture 보존은 승인된 로컬 공기계 경로에만 둔다.

배포가 실패하면 Azure DevOps의 `HTTPS health 스모크` 단계와 Container App revision 상태를 먼저 본다.
`/health`가 `stub_llm:false`가 아니면 Key Vault secret 이름·Managed Identity RBAC·환경변수 구성을
확인한다. 정상 health 뒤 Gemini 요청만 실패하면 서버의 비식별 `request_id`와 Logical Request ID로
Console logs를 대조한다.
