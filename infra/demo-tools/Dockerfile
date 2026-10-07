# The demo-tools image (infra/deploy): the till simulator of the backend's test sources, which
# writes the demo's eight weeks of till sales through the sync contract (DemoTillHistory, the
# last step of `make demo-data`). A demo server has no JDK or Gradle, so CI packs the class path
# here and infra/deploy/demo-data.sh runs it with `docker compose run demo-tools`.
#
# Build context: backend/app, after `./gradlew :app:demoToolsDist` (which fills build/demo-tools).
#   docker build -f infra/demo-tools/Dockerfile -t coop-erp/demo-tools:dev backend/app
#
# Settings (environment): COOP_ERP_API, COOP_ERP_TOKEN_URL, COOP_ERP_DEMO_PASSWORD. The default
# command is the till history; `... demo-tools lk.coopfed.knoweb.demo.DemoTillSale` makes one sale.
#
# The base is pinned by digest (wave 2, DEPLOY-15); Dependabot proposes the next one. The same
# digest is jibBaseImage in backend/gradle.properties and the first stage of infra/worker-image.
FROM eclipse-temurin:21-jre@sha256:cff19e6215689161eb6162c11b86b0c60ddf802164f2eaf48d570f8fb79a36c5
COPY build/demo-tools /app
USER 10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-cp", "/app/classes:/app/lib/*"]
CMD ["lk.coopfed.knoweb.demo.DemoTillHistory"]
