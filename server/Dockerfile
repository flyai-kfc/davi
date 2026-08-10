# Azure Container Apps용 개발 서버 이미지. 실행 설정과 비밀값은 배포 환경에서만 주입한다.
FROM python:3.12-slim-bookworm@sha256:4766d8b510c428e595d74b9cc5bbb2fae8e26316fffb4adc89908d79aacd58a2

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

RUN groupadd --gid 10001 davi \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin davi

WORKDIR /app

COPY requirements.lock.txt ./
RUN python -m pip install --requirement requirements.lock.txt

COPY --chown=davi:davi app ./app

USER davi
EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=2).read()"]

CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1", "--no-access-log", "--log-config", "app/logging.json"]
