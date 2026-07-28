# Tapstate server image.
#
# Ships the Spring Boot repackaged jar, not the dist tar.gz: the jar's own manifest carries
# Add-Opens: java.base/java.lang, so a bare `java -jar` has the reflective open a PDK connector's
# config binding needs, with no command-line flags to keep in sync.
#
# Build context is the repository root, because the jar is read from app/target:
#   mvn -pl app -am -DskipTests package
#   docker build -f deploy/docker/Dockerfile -t tapstate:dev .
#
# Connector jars are deliberately NOT baked in. They are registered at runtime by uploading their
# bytes, which keeps a connector's lifecycle independent of this image's version.

FROM eclipse-temurin:21-jre

# Image self-description (OCI). The source label connects this package to its repository, so the
# registry renders the repository README on the package page; the description states the preview
# posture up front so the image is not mistaken for a production default.
LABEL org.opencontainers.image.title="Tapstate server" \
      org.opencontainers.image.description="Preview runtime: single-node, in-memory; a restart replays from the source rather than resuming a persisted offset. Not for production." \
      org.opencontainers.image.source="https://github.com/tapstate/tapstate" \
      org.opencontainers.image.url="https://tapstate.com" \
      org.opencontainers.image.documentation="https://github.com/tapstate/tapstate/blob/main/docs/quickstart-online.md" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.vendor="Tapstate"

# curl serves the container healthcheck and leaves a way to inspect the running server from inside.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# A fixed, unprivileged uid: the server needs no root capability, and a numeric id keeps the
# ownership legible when a host directory is mounted in.
RUN groupadd --gid 10001 tapstate \
 && useradd --uid 10001 --gid 10001 --create-home --shell /usr/sbin/nologin tapstate

# Several settings default to paths relative to the working directory (the plugin directory the
# server unpacks into, the log directory, the connector seed directory). WORKDIR therefore has to be
# a directory the unprivileged user can write, and it is where a host mount is expected to land.
WORKDIR /var/lib/tapstate
RUN mkdir -p /var/lib/tapstate/plugins /var/lib/tapstate/logs /var/lib/tapstate/connectors \
 && chown -R tapstate:tapstate /var/lib/tapstate

# The wildcard tolerates the version in the artifact name without pinning it here; the build fails
# loudly if it matches anything other than exactly one file.
COPY --chown=tapstate:tapstate app/target/app-*-boot.jar /opt/tapstate/tapstate.jar

USER tapstate

EXPOSE 8080

# The endpoint is pure liveness: it answers 200 while this process serves HTTP and asserts nothing
# about the store. That is sufficient here only because the server refuses to finish starting when
# the store is unreachable, so "serving HTTP at all" already implies the store verified once.
HEALTHCHECK --interval=5s --timeout=3s --start-period=60s --retries=24 \
  CMD curl -fsS http://127.0.0.1:8080/healthz || exit 1

# Exec form, so java is PID 1 and receives SIGTERM directly. The server installs its own shutdown
# hook that closes the context and then halts with status 0, so a stopped container reports 0 rather
# than the 128+15 the JVM would otherwise exit with.
ENTRYPOINT ["java", "-jar", "/opt/tapstate/tapstate.jar"]

# Overridable: replacing it is how an operator passes a different role or extra Spring arguments.
CMD ["--role=all"]
