// release APK가 사용하는 외부 공개 Container App revision.
// ACR·Key Vault·Container Apps 환경·managed identity는 dev-foundation.bicep의 기존 자원을
// 같은 resource group에서 재사용한다. 배포 이름은 release지만 서버의 안전 프로필은 production이다.
targetScope = 'resourceGroup'

@minLength(1)
param containerAppName string = 'davi-server-release'

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
param imageTag string

@minLength(2)
@maxLength(64)
param revisionSuffix string

@minLength(1)
param imageRepository string = 'davi-server'

@allowed([
  'openai'
])
@description('현재 release 동의·처리방침에 고지된 공급자만 허용한다. 변경 시 고지·재동의도 함께 갱신한다.')
param llmProvider string

@description('비우면 서버가 공급자 기본 모델을 고른다.')
param daviModel string = ''

@minLength(1)
param geminiApiKeySecretName string = 'GEMINI-API-KEY'

@minLength(1)
@description('release OpenAI 호출에 필요한 Key Vault secret 이름.')
param openAiApiKeySecretName string

@minLength(1)
@description('release 세션 JWT 서명 비밀의 Key Vault secret 이름. 값은 32자 이상이어야 한다.')
param authJwtSecretSecretName string

@minLength(1)
@description('카카오 개발자 콘솔의 숫자 앱 ID. 네이티브 앱 키가 아니다.')
param kakaoAppId string

var commonTags = {
  environment: 'release'
  runtimeProfile: 'production'
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
      secrets: concat([
        {
          name: 'gemini-api-key'
          keyVaultUrl: 'https://${keyVault.name}${environment().suffixes.keyvaultDns}/secrets/${geminiApiKeySecretName}'
          identity: containerIdentity.id
        }
        {
          name: 'auth-jwt-secret'
          keyVaultUrl: 'https://${keyVault.name}${environment().suffixes.keyvaultDns}/secrets/${authJwtSecretSecretName}'
          identity: containerIdentity.id
        }
      ], empty(openAiApiKeySecretName) ? [] : [
        {
          name: 'openai-api-key'
          keyVaultUrl: 'https://${keyVault.name}${environment().suffixes.keyvaultDns}/secrets/${openAiApiKeySecretName}'
          identity: containerIdentity.id
        }
      ])
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
              value: 'production'
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
              name: 'DAVI_AUTH_SOURCE'
              value: 'azure_container_apps'
            }
            {
              name: 'DAVI_LLM_PROVIDER'
              value: llmProvider
            }
            {
              name: 'DAVI_MODEL'
              value: daviModel
            }
            {
              name: 'DAVI_DOGFOOD_DEVICE_IDS'
              value: ''
            }
            {
              name: 'DAVI_KAKAO_APP_ID'
              value: kakaoAppId
            }
            {
              name: 'DAVI_AUTH_JWT_SECRET'
              secretRef: 'auth-jwt-secret'
            }
            {
              name: 'GEMINI_API_KEY'
              secretRef: 'gemini-api-key'
            }
          ], empty(openAiApiKeySecretName) ? [] : [
            {
              name: 'OPENAI_API_KEY'
              secretRef: 'openai-api-key'
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
      // rate limiter가 현재 프로세스 메모리에 있으므로 여러 replica로 분산하지 않는다.
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
