from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]


def _text(relative_path: str) -> str:
    return (REPO_ROOT / relative_path).read_text(encoding="utf-8")


def test_container_image_runs_as_non_root_without_local_or_raw_data() -> None:
    dockerfile = _text("server/Dockerfile")
    dockerignore = _text("server/.dockerignore")
    runtime_lock = _text("server/requirements.lock.txt")

    assert "FROM python:3.12-slim-bookworm@sha256:" in dockerfile
    assert "COPY requirements.lock.txt ./" in dockerfile
    assert "--requirement requirements.lock.txt" in dockerfile
    assert "COPY requirements.txt ./" not in dockerfile
    assert ">=" not in runtime_lock
    assert "USER davi" in dockerfile
    assert "COPY --chown=davi:davi app ./app" in dockerfile
    assert "COPY ." not in dockerfile
    assert '--workers", "1"' in dockerfile
    for excluded_path in (".env", ".venv/", ".shared-dev/", ".dogfood-raw/"):
        assert excluded_path in dockerignore


def test_container_app_uses_single_https_development_revision() -> None:
    template = _text("infra/azure/dev-server.bicep")

    assert "param containerAppName string = 'davi-server-app'" in template
    assert "revisionSuffix: revisionSuffix" in template
    assert "external: true" in template
    assert "allowInsecure: false" in template
    assert "targetPort: 8000" in template
    assert "minReplicas: 1" in template
    assert "maxReplicas: 1" in template
    assert "name: 'DAVI_ENV'\n              value: 'development'" in template
    assert "name: 'DAVI_USE_STUB_LLM'\n              value: 'false'" in template
    assert "name: 'DAVI_DOGFOOD_DEVICE_IDS'\n              value: ''" in template


def test_container_app_reads_secrets_only_through_managed_identity() -> None:
    foundation = _text("infra/azure/dev-foundation.bicep")
    template = _text("infra/azure/dev-server.bicep")

    assert "adminUserEnabled: false" in foundation
    assert "enableRbacAuthorization: true" in foundation
    assert "AcrPull" in foundation
    assert "Key Vault Secrets User" in foundation
    assert "keyVaultUrl:" in template
    assert "identity: containerIdentity.id" in template
    assert "name: 'GEMINI_API_KEY'\n              secretRef: 'gemini-api-key'" in template
    assert "name: 'DAVI_DEV_ACCESS_TOKEN'\n              secretRef: 'dev-access-token'" in template
    assert (
        "name: 'DAVI_TEST_ACCESS_TOKEN'\n              secretRef: 'test-access-token'" in template
    )


def test_external_test_token_secret_is_optional_so_deploys_do_not_depend_on_it() -> None:
    """secret을 만들기 전에 머지해도 배포가 깨지지 않아야 한다.

    Container Apps는 revision을 만들 때 `keyVaultUrl`을 해석한다. 없는 secret을 조건 없이
    참조하면 새 revision이 Healthy에 도달하지 못하고, 배포 워크플로의 revision·health 검증이
    실패해 **이후 배포까지 막힌다**(PR #257 리뷰). 그래서 기본값을 비우고 양쪽에 가드를 건다.
    """

    template = _text("infra/azure/dev-server.bicep")

    assert "param testAccessTokenSecretName string = ''" in template
    # secret 항목과 env 항목 **양쪽**에 가드가 걸려야 한 쪽만 남는 반쪽 배선이 안 생긴다.
    assert template.count("empty(testAccessTokenSecretName)") == 2
    assert "@minLength(1)\n@description('외부 테스트 참가자용" not in template


def test_development_key_vault_uses_short_recoverable_retention() -> None:
    foundation = _text("infra/azure/dev-foundation.bicep")

    assert "softDeleteRetentionInDays: 7" in foundation
    assert "enablePurgeProtection" not in foundation


def test_github_deploy_never_deploys_pr_and_uses_oidc() -> None:
    workflow = _text(".github/workflows/azure-dev-deploy.yml")

    assert "pull_request:" not in workflow
    assert "branches: [main]" in workflow
    assert "if: github.ref == 'refs/heads/main'" in workflow
    assert "environment: davi-development" in workflow
    assert "id-token: write" in workflow
    assert "uses: azure/login@v2" in workflow
    assert "client-secret:" not in workflow
    assert "${{ secrets." not in workflow


def test_github_deploy_uses_unique_local_image_and_bicep_revision() -> None:
    workflow = _text(".github/workflows/azure-dev-deploy.yml")

    assert "az acr build" not in workflow
    assert "az containerapp up" not in workflow
    assert 'az acr login --name "$DAVI_AZURE_ACR_NAME"' in workflow
    assert "docker build" in workflow
    assert "--platform linux/amd64" in workflow
    assert "--file server/Dockerfile" in workflow
    assert 'docker push "$login_server/$IMAGE_REPOSITORY:$image_tag"' in workflow
    assert 'image_tag="${GITHUB_SHA}-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"' in workflow
    assert 'revision_suffix="r${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"' in workflow
    assert "az deployment group create" in workflow
    assert "--template-file infra/azure/dev-server.bicep" in workflow
    assert "GEMINI_API_KEY" not in workflow
    assert "DAVI_DEV_ACCESS_TOKEN" not in workflow
    # 서명 비밀은 Key Vault에만 있다. 워크플로가 다루는 것은 secret **이름**뿐이다.
    assert "DAVI_AUTH_JWT_SECRET" not in workflow
    assert not (REPO_ROOT / "azure-pipelines.yml").exists()


def test_github_deploy_verifies_exact_revision_and_image_before_health() -> None:
    workflow = _text(".github/workflows/azure-dev-deploy.yml")

    revision_check = workflow.index("az containerapp revision show")
    health_check = workflow.index('"https://$DEPLOYED_FQDN/health"')

    assert "properties.outputs" in workflow
    assert ".deployedRevision.value" in workflow
    assert ".deployedImage.value" in workflow
    assert ".containerAppFqdn.value" in workflow
    assert "EXPECTED_REVISION: ${{ steps.deploy.outputs.revision }}" in workflow
    assert "EXPECTED_IMAGE: ${{ steps.deploy.outputs.image }}" in workflow
    assert ".name == $expected_revision" in workflow
    assert '.properties.provisioningState == "Provisioned"' in workflow
    assert '.properties.healthState == "Healthy"' in workflow
    assert ".properties.trafficWeight == 100" in workflow
    assert ".properties.template.containers[0].image == $expected_image" in workflow
    assert revision_check < health_check


def test_infrastructure_ci_runs_when_container_inputs_change() -> None:
    workflow = _text(".github/workflows/ci.yml")

    assert "- 'server/app/**'" in workflow
    assert "- 'server/requirements.lock.txt'" in workflow


def test_deploy_workflow_passes_the_test_token_secret_name() -> None:
    """수동 배선한 테스트 토큰이 다음 main 푸시에 조용히 날아가지 않게 한다.

    bicep 기본값이 비어 있으므로(위 테스트), 워크플로가 이름을 넘기지 않으면 **모든 자동
    배포가 테스트 토큰 없는 revision을 만든다.** 배포는 성공하고 참가자만 401이 되므로
    로그에 단서가 없다(PR #257 재리뷰).

    변수는 **선택**이라 필수 변수 검사 목록에는 없어야 한다 — 평소에는 비어 있는 것이 정상이고,
    거기 들어가면 테스트 기간이 아닐 때 모든 배포가 실패한다.
    """

    workflow = _text(".github/workflows/azure-dev-deploy.yml")

    assert "DAVI_AZURE_TEST_TOKEN_SECRET_NAME: ${{ vars.DAVI_AZURE_TEST_TOKEN_SECRET_NAME }}" in (
        workflow
    )
    assert 'testAccessTokenSecretName="${DAVI_AZURE_TEST_TOKEN_SECRET_NAME:-}"' in workflow

    required_block = workflow[workflow.index("for name in") : workflow.index("test -n ")]
    assert "DAVI_AZURE_TEST_TOKEN_SECRET_NAME" not in required_block


def test_kakao_auth_wiring_is_optional_so_deploys_do_not_depend_on_it() -> None:
    """카카오 값이 없어도 배포가 돌아야 한다(#279).

    테스트 토큰과 같은 이유다 — 없는 Key Vault secret을 조건 없이 참조하면 revision이
    Healthy에 도달하지 못해 이후 배포까지 막힌다. 앱 ID는 secret이 아니라 평문 env지만,
    **빈 문자열을 실으면 서버의 int 파싱이 실패해** 결국 같은 자리에서 막힌다. 그래서
    비었을 때 env 항목 자체를 뺀다.
    """

    template = _text("infra/azure/dev-server.bicep")

    assert "param authJwtSecretSecretName string = ''" in template
    assert "param kakaoAppId string = ''" in template
    # 서명 비밀은 secret 항목과 env 항목 양쪽에 가드가 걸린다.
    assert template.count("empty(authJwtSecretSecretName)") == 2
    # 앱 ID는 비밀이 아니라 Key Vault를 거치지 않는다 — 가드가 걸릴 자리는 env 하나뿐이다.
    assert template.count("empty(kakaoAppId)") == 1
    assert "keyVaultUrl: 'https://${keyVault.name}${environment().suffixes.keyvaultDns}" in template
    assert "name: 'DAVI_AUTH_JWT_SECRET'\n              secretRef: 'auth-jwt-secret'" in template
    assert "name: 'DAVI_KAKAO_APP_ID'\n              value: kakaoAppId" in template


def test_deploy_workflow_passes_and_verifies_the_kakao_auth_wiring() -> None:
    """배선이 자동 배포에서 살아남고, 살아남았는지 배포가 스스로 확인한다(#279).

    손으로 배선하면 다음 자동 배포가 조용히 지운다. 그리고 `/health`를 안 보면 배선이
    빠진 배포도 초록이다 — `/v1/auth/kakao`는 개발 토큰 게이트 뒤라 워크플로가 찌를 수 없다.
    """

    workflow = _text(".github/workflows/azure-dev-deploy.yml")

    assert (
        "DAVI_AZURE_AUTH_JWT_SECRET_NAME: ${{ vars.DAVI_AZURE_AUTH_JWT_SECRET_NAME }}" in workflow
    )
    assert "DAVI_AZURE_KAKAO_APP_ID: ${{ vars.DAVI_AZURE_KAKAO_APP_ID }}" in workflow
    assert 'authJwtSecretSecretName="${DAVI_AZURE_AUTH_JWT_SECRET_NAME:-}"' in workflow
    assert 'kakaoAppId="${DAVI_AZURE_KAKAO_APP_ID:-}"' in workflow

    # 평소에는 비어 있는 것이 정상이므로 필수 변수 검사에 들어가면 안 된다.
    required_block = workflow[workflow.index("for name in") : workflow.index("test -n ")]
    assert "DAVI_AZURE_AUTH_JWT_SECRET_NAME" not in required_block
    assert "DAVI_AZURE_KAKAO_APP_ID" not in required_block

    # 숫자가 아닌 앱 ID는 기동 실패 → revision unhealthy → 배포 차단이므로 배포 전에 끊는다.
    assert '! [[ "$DAVI_AZURE_KAKAO_APP_ID" =~ ^[1-9][0-9]*$ ]]' in workflow

    # 채웠으면 true, 비웠으면 false. 등호 비교라 누락과 "폐기 후 남은 배선"을 모두 잡는다.
    assert "expected_kakao_auth=true" in workflow
    assert "expected_kakao_auth=false" in workflow
    assert ".kakao_auth_configured == $expected_kakao_auth" in workflow
