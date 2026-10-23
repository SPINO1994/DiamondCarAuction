FROM golang:1.24.9-alpine3.22 AS build
ENV CGO_ENABLED=0 GOBIN=/out
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
    go install -p 2 github.com/minio/minio@RELEASE.2025-10-15T17-29-55Z
FROM alpine:3.22.2
RUN addgroup -S minio && adduser -S -G minio minio && mkdir /data && chown minio:minio /data
COPY --from=build /out/minio /usr/local/bin/minio
USER minio
ENTRYPOINT ["minio"]
