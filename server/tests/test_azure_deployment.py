from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]


def _text(relative_path: str) -> str:
    return (REPO_ROOT / relative_path).read_text(encoding="utf-8")


def test_container_image_runs_as_non_root_without_local_or_raw_data() -> None:
    dockerfile = _text("server/Dockerfile")
    dockerignore = _text("server/.dockerignore")

    assert "USER davi" in dockerfile
    assert "COPY --chown=davi:davi app ./app" in dockerfile
    assert "COPY ." not in dockerfile
    assert '--workers", "1"' in dockerfile
    for excluded_path in (".env", ".venv/", ".shared-dev/", ".dogfood-raw/"):
        assert excluded_path in dockerignore


def test_container_app_uses_single_https_development_revision() -> None:
    template = _text("infra/azure/dev-server.bicep")

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


def test_pipeline_never_deploys_pr_and_uses_unique_image_and_revision() -> None:
    pipeline = _text("azure-pipelines.yml")

    assert "      - main" in pipeline
    assert "pr: none" in pipeline
    assert "az acr build" not in pipeline
    assert 'az acr login --name "$DAVI_AZURE_ACR_NAME"' in pipeline
    assert "docker build" in pipeline
    assert "--platform linux/amd64" in pipeline
    assert "--file server/Dockerfile" in pipeline
    assert 'docker push "$login_server/$(imageRepository):$image_tag"' in pipeline
    assert 'image_tag="$(Build.SourceVersion)-$(Build.BuildId)"' in pipeline
    assert 'revision_suffix="r$(Build.BuildId)"' in pipeline
    assert "GEMINI_API_KEY: $(" not in pipeline
    assert "DAVI_DEV_ACCESS_TOKEN: $(" not in pipeline
