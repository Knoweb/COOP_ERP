# The base of the worker image (K-06b; decided 27 Sep 2026, PR #145): the same Java runtime as
# the backend image (eclipse-temurin:21-jre, copied in, so both roles run one JRE) and a
# headless Chromium for the A4 renderer (19A section 6, DR-3). Only the worker role renders, so
# only this image carries a browser; the web and ingest roles keep the plain JRE image.
#
# `make image` builds this, then Jib puts the application on top of it
# (coop-erp/backend-worker:dev, -PjibWorker=true). No fonts are installed: the renderer embeds
# the three Noto families it ships in the page itself.
#
# Debian 13 packages Chromium as a plain package (Ubuntu, the base of eclipse-temurin, ships
# it as a snap, which does not run in a container).
FROM eclipse-temurin:21-jre AS jre

FROM debian:trixie-slim
RUN apt-get update \
    && apt-get install --yes --no-install-recommends chromium ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY --from=jre /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk \
    PATH=/opt/java/openjdk/bin:$PATH \
    CHROMIUM_PATH=/usr/bin/chromium
