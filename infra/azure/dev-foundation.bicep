// Azure 공유 개발 서버의 장기 기반 리소스.
// RBAC role assignment를 포함하므로 권한 보유자가 최초 1회 배포한다.
targetScope = 'resourceGroup'

@description('Azure 리전. 기본값은 대상 Resource Group의 리전이다.')
param location string = resourceGroup().location

@minLength(5)
@maxLength(50)
@description('전역적으로 고유한 영문·숫자 Azure Container Registry 이름이다.')
param acrName string

@minLength(3)
@maxLength(24)
@description('전역적으로 고유한 Key Vault 이름이다.')
param keyVaultName string

@minLength(1)
param managedEnvironmentName string = 'acaenv-davi-dev'

@minLength(4)
param logAnalyticsWorkspaceName string = 'log-davi-dev'

@minLength(1)
param containerIdentityName string = 'id-davi-server-dev'

var commonTags = {
  environment: 'development'
  project: 'davi'
  managedBy: 'bicep'
}
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
  tags: commonTags
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
  tags: commonTags
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
  tags: commonTags
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
  tags: commonTags
  properties: {
    tenantId: subscription().tenantId
    sku: {
      family: 'A'
      name: 'standard'
    }
    enableRbacAuthorization: true
    // dev vault는 이름·환경을 짧게 재생성할 수 있도록 최소 soft-delete 기간만 둔다.
    // purge protection은 비가역이므로 dev foundation에서는 활성화하지 않는다.
    softDeleteRetentionInDays: 7
    publicNetworkAccess: 'Enabled'
  }
}

resource containerIdentity 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = {
  name: containerIdentityName
  location: location
  tags: commonTags
}

resource registryPullRole 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
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
