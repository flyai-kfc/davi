// main 자동 배포 Pipeline이 적용하는 공유 development Container App revision.
// 기반 리소스와 RBAC는 dev-foundation.bicep에서 최초 1회 준비한다.
targetScope = 'resourceGroup'

@minLength(1)
param containerAppName string = 'davi-server-dev'

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
@description('ACR에 이미 push된 불변 image tag다. commit SHA와 Pipeline build ID를 함께 쓴다.')
param imageTag string

@minLength(2)
@maxLength(64)
@description('같은 commit 재실행도 새 revision이 되게 Pipeline build ID에서 만든 suffix다.')
param revisionSuffix string

@minLength(1)
param imageRepository string = 'davi-server'

@minLength(1)
param daviModel string = 'gemini-3.5-flash-lite'

@minLength(1)
param geminiApiKeySecretName string = 'GEMINI-API-KEY'

@minLength(1)
param devAccessTokenSecretName string = 'DAVI-DEV-ACCESS-TOKEN'

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
      secrets: [
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
      ]
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
          env: [
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
          ]
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
