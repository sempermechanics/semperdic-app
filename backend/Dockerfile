# Pinned by digest for reproducible builds: the human tag can move, the digest
# cannot. Re-resolve with `docker buildx imagetools inspect python:3.12-slim`
# when intentionally bumping.
FROM python:3.14-slim@sha256:caaf356f40667c496d405780745b9ac25771c189a51dfcc42430d531ea09f8a2
WORKDIR /app
# Install from the hashed lock, not requirements.txt. requirements.txt pins only
# our direct dependencies, so transitive ones floated between builds — the image
# digest was reproducible but its contents were not. --require-hashes makes pip
# refuse anything whose artifact does not match, which also blocks a tampered or
# substituted package. Regenerate with:
#   pip-compile --generate-hashes --output-file=requirements.lock requirements.txt
COPY requirements.lock .
RUN pip install --no-cache-dir --require-hashes -r requirements.lock
COPY app/ ./app/
ENV PORT=8080

# Run as a non-root user rather than root (the default). Nothing here needs
# privilege at runtime, and Cloud Run has no reason to run this as uid 0.
RUN useradd --system --no-create-home --uid 10001 appuser
USER appuser

# One worker. Cloud Run scales by instance, and with 1 vCPU a second process
# only doubled the import on a cold start (~6 s) and split the per-process rate
# buckets in two. Sync routes still run concurrently on the threadpool.
CMD exec uvicorn app.main:app --host 0.0.0.0 --port ${PORT} --workers 1
