FROM python:3.12-slim

WORKDIR /app
COPY pyproject.toml ./
RUN pip install --no-cache-dir uv \
    && uv sync --no-dev
COPY app ./app
COPY fixtures ./fixtures
RUN mkdir -p /app/artifacts

EXPOSE 8081
CMD ["uv", "run", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8081"]
