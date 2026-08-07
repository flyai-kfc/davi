// Azure 개발 서버의 장기 기반 리소스.
// 이 파일은 Key Vault RBAC role assignment를 만들므로 Azure 구독 관리자/권한 보유자가 한 번만 실행한다.
// main 자동 배포 Pipeline은 이 리소스를 다시 만들지 않고 dev-server.bicep만 적용한다.
targetScope = 'resourceGroup'

@description('Azure 리전. 기본값은 대상 Resource Group의 리전이다.')
param location string = resourceGroup().location

@minLength(5)
@maxLength(50)
@description('전역적으로 고유한 영문·숫자 Azure Container Registry 이름이다.')
param acrName string

@minLength(3)
@maxLength(24)
@description('전역적으로 고유한 영문·숫자·하이픈 Key Vault 이름이다.')
param keyVaultName string

@minLength(1)
param managedEnvironmentName string = 'acaenv-davi-dev'

@minLength(4)
param logAnalyticsWorkspaceName string = 'log-davi-dev'

@minLength(1)
param containerIdentityName string = 'id-davi-server-dev'

var acrPullRoleDefinitionId = subscriptionResourceId(
  'Microsoft.Authorization/roleDefinitions',
  '7f951dda-4ed3-4680-a7ca-43fe172d538d' // AcrPull
)
var keyVaultSecretsUserRoleDefinitionId = subscriptionResourceId(
  'Microsoft.Authorization/roleDefinitions',
  '4633458b-17de-408a-b874-0445c86b69e6' // Key Vault Secrets User
)

resource logAnalyticsWorkspace 'Microsoft.OperationalInsights/workspaces@2023-09-01' = {
  name: logAnalyticsWorkspaceName
  location: location
  properties: {
    sku: {
      name: 'PerGB2018'
    }
    retentionInDays: 30
    features: {
      legacy: 0
      searchVersion: 1
    }
  }
}

resource managedEnvironment 'Microsoft.App/managedEnvironments@2024-03-01' = {
  name: managedEnvironmentName
  location: location
  properties: {
    appLogsConfiguration: {
      destination: 'log-analytics'
      logAnalyticsConfiguration: {
        customerId: logAnalyticsWorkspace.properties.customerId
        sharedKey: logAnalyticsWorkspace.listKeys().primarySharedKey
      }
    }
  }
}

resource registry 'Microsoft.ContainerRegistry/registries@2023-07-01' = {
  name: acrName
  location: location
  sku: {
    name: 'Basic'
  }
  properties: {
    adminUserEnabled: false
    publicNetworkAccess: 'Enabled'
  }
}

resource keyVault 'Microsoft.KeyVault/vaults@2023-07-01' = {
  name: keyVaultName
  location: location
  properties: {
    tenantId: subscription().tenantId
    sku: {
      family: 'A'
      name: 'standard'
    }
    enableRbacAuthorization: true
    enablePurgeProtection: true
    publicNetworkAccess: 'Enabled'
  }
}

resource containerIdentity 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = {
  name: containerIdentityName
  location: location
}

resource registryPullRole 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  // role assignment name은 deployment 시작 시 계산 가능해야 한다. principalId는 생성 뒤에만 생긴다.
  name: guid(registry.id, containerIdentity.id, acrPullRoleDefinitionId)
  scope: registry
  properties: {
    principalId: containerIdentity.properties.principalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: acrPullRoleDefinitionId
  }
}

resource keyVaultSecretsRole 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(keyVault.id, containerIdentity.id, keyVaultSecretsUserRoleDefinitionId)
  scope: keyVault
  properties: {
    principalId: containerIdentity.properties.principalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: keyVaultSecretsUserRoleDefinitionId
  }
}

output managedEnvironmentId string = managedEnvironment.id
output registryLoginServer string = registry.properties.loginServer
output containerIdentityResourceId string = containerIdentity.id
output keyVaultUri string = keyVault.properties.vaultUri
