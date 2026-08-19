// main 자동 배포 GitHub Actions workflow가 적용하는 공유 development Container App revision.
// 기반 리소스와 RBAC는 dev-foundation.bicep에서 최초 1회 준비한다.
targetScope = 'resourceGroup'

@minLength(1)
param containerAppName string = 'davi-server-app'

@minLength(1)
param managedEnvironmentName string

@minLength(5)
@maxLength(50)
param acrName string

@minLength(3)
@maxLength(24)
param keyVaultName string

@minLength(1)
param containerIdentityName string

@minLength(7)
@maxLength(128)
@description('ACR에 이미 push된 불변 image tag다. commit SHA와 GitHub Actions run ID/attempt를 함께 쓴다.')
param imageTag string

@minLength(2)
@maxLength(64)
@description('같은 commit 재실행도 새 revision이 되게 GitHub Actions run ID/attempt에서 만든 suffix다.')
param revisionSuffix string

@minLength(1)
param imageRepository string = 'davi-server'

@minLength(1)
param daviModel string = 'gemini-3.5-flash-lite'

@minLength(1)
param geminiApiKeySecretName string = 'GEMINI-API-KEY'

@minLength(1)
param devAccessTokenSecretName string = 'DAVI-DEV-ACCESS-TOKEN'

@description('외부 테스트 참가자용 둘째 토큰의 Key Vault secret 이름. **비우면 아예 배선하지 않는다**(기본값). Container Apps는 revision을 만들 때 keyVaultUrl을 해석하므로, 없는 secret을 참조하면 revision이 Healthy에 도달하지 못해 이후 배포까지 막힌다. 그래서 secret 생성과 이 템플릿의 머지 순서에 의존하지 않도록 선택으로 둔다. 테스트 기간에만 이 파라미터를 넘기고, 끝나면 빼는 것으로 폐기가 끝난다.')
param testAccessTokenSecretName string = ''

@description('세션 JWT 서명 비밀(DAVI_AUTH_JWT_SECRET)의 Key Vault secret 이름. **비우면 아예 배선하지 않는다**(기본값). 이유는 위 테스트 토큰과 같다 — secret 생성 순서가 배포를 막지 않아야 한다. 값이 32자 미만이면 서버가 기동 시 거부하므로 revision이 Healthy에 도달하지 못한다.')
param authJwtSecretSecretName string = ''

@description('카카오 개발자 콘솔의 앱 ID(숫자 문자열). 비밀이 아니라 GitHub environment 변수로 넘긴다 — 토큰 발급처 대조용이며 REST API 키·네이티브 앱 키가 아니다. **비우면 env 항목 자체를 뺀다**: 빈 문자열을 실으면 서버의 int 파싱이 실패해 컨테이너가 기동하지 못한다.')
param kakaoAppId string = ''

var commonTags = {
  environment: 'development'
  project: 'davi'
  managedBy: 'bicep'
}

resource managedEnvironment 'Microsoft.App/managedEnvironments@2024-03-01' existing = {
  name: managedEnvironmentName
}

resource registry 'Microsoft.ContainerRegistry/registries@2023-07-01' existing = {
  name: acrName
}

resource keyVault 'Microsoft.KeyVault/vaults@2023-07-01' existing = {
  name: keyVaultName
}

resource containerIdentity 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' existing = {
  name: containerIdentityName
}

resource containerApp 'Microsoft.App/containerApps@2024-03-01' = {
  name: containerAppName
  location: resourceGroup().location
  tags: commonTags
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: {
      '${containerIdentity.id}': {}
    }
  }
  properties: {
    managedEnvironmentId: managedEnvironment.id
    configuration: {
      activeRevisionsMode: 'Single'
      ingress: {
        external: true
        targetPort: 8000
        transport: 'auto'
        allowInsecure: false
        traffic: [
          {
            latestRevision: true
            weight: 100
          }
        ]
      }
      registries: [
        {
          server: registry.properties.loginServer
          identity: containerIdentity.id
        }
      ]
      secrets: concat(
        [
          {
            name: 'gemini-api-key'
            keyVaultUrl: 'https://${keyVault.name}${environment().suffixes.keyvaultDns}/secrets/${geminiApiKeySecretName}'
            identity: containerIdentity.id
          }
          {
            name: 'dev-access-token'
            keyVaultUrl: 'https://${keyVault.name}${environment().suffixes.keyvaultDns}/secrets/${devAccessTokenSecretName}'
            identity: containerIdentity.id
          }
        ],
        empty(testAccessTokenSecretName)
          ? []
          : [
              {
                name: 'test-access-token'
                keyVaultUrl: 'https://${keyVault.name}${environment().suffixes.keyvaultDns}/secrets/${testAccessTokenSecretName}'
                identity: containerIdentity.id
              }
            ],
        empty(authJwtSecretSecretName)
          ? []
          : [
              {
                name: 'auth-jwt-secret'
                keyVaultUrl: 'https://${keyVault.name}${environment().suffixes.keyvaultDns}/secrets/${authJwtSecretSecretName}'
                identity: containerIdentity.id
              }
            ]
      )
    }
    template: {
      revisionSuffix: revisionSuffix
      containers: [
        {
          name: 'server'
          image: '${registry.properties.loginServer}/${imageRepository}:${imageTag}'
          resources: {
            cpu: json('0.5')
            memory: '1Gi'
          }
          env: concat([
            {
              name: 'DAVI_ENV'
              value: 'development'
            }
            {
              name: 'DAVI_USE_STUB_LLM'
              value: 'false'
            }
            {
              name: 'DAVI_BIND_HOST'
              value: '0.0.0.0'
            }
            {
              name: 'DAVI_MODEL'
              value: daviModel
            }
            {
              name: 'DAVI_DEV_RATE_LIMIT_REQUESTS'
              value: '10'
            }
            {
              name: 'DAVI_DEV_RATE_LIMIT_WINDOW_SECONDS'
              value: '60'
            }
            {
              name: 'DAVI_DOGFOOD_DEVICE_IDS'
              value: ''
            }
            {
              name: 'GEMINI_API_KEY'
              secretRef: 'gemini-api-key'
            }
            {
              name: 'DAVI_DEV_ACCESS_TOKEN'
              secretRef: 'dev-access-token'
            }
          ], empty(testAccessTokenSecretName) ? [] : [
            {
              name: 'DAVI_TEST_ACCESS_TOKEN'
              secretRef: 'test-access-token'
            }
          ], empty(authJwtSecretSecretName) ? [] : [
            {
              name: 'DAVI_AUTH_JWT_SECRET'
              secretRef: 'auth-jwt-secret'
            }
          ], empty(kakaoAppId) ? [] : [
            {
              name: 'DAVI_KAKAO_APP_ID'
              value: kakaoAppId
            }
          ])
          probes: [
            {
              type: 'Liveness'
              httpGet: {
                path: '/health'
                port: 8000
              }
              initialDelaySeconds: 10
              periodSeconds: 15
              timeoutSeconds: 3
              failureThreshold: 3
            }
            {
              type: 'Readiness'
              httpGet: {
                path: '/health'
                port: 8000
              }
              initialDelaySeconds: 5
              periodSeconds: 10
              timeoutSeconds: 3
              failureThreshold: 3
            }
          ]
        }
      ]
      scale: {
        minReplicas: 1
        maxReplicas: 1
      }
    }
  }
}

output containerAppFqdn string = containerApp.properties.configuration.ingress.fqdn
output deployedImage string = '${registry.properties.loginServer}/${imageRepository}:${imageTag}'
output deployedRevision string = '${containerApp.name}--${revisionSuffix}'
