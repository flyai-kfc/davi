# Azure Container Apps용 서버 이미지. 실행 설정·비밀값은 이미지가 아니라 배포 환경에서 주입한다.
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /app

COPY requirements.txt ./
RUN python -m pip install --upgrade pip && python -m pip install -r requirements.txt

COPY app ./app

EXPOSE 8000

# Container Apps가 외부에서 8000 포트로 전달한다. access log는 앱의 비식별 JSON 로그와
# 중복되고 IP/port를 보존하므로 끈다.
CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1", "--no-access-log", "--log-config", "app/logging.json"]
