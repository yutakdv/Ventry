FROM python:3.11-slim

# libgomp1: lightgbm 런타임 / make: eval 하네스 실행
RUN apt-get update \
    && apt-get install -y --no-install-recommends make libgomp1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

# 배치는 compose 실행 경로에서 제외 (스펙 §8) — 수동 실행 컨테이너
CMD ["make", "help"]
