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
FROM eclipse-temurin:21-jre
COPY build/demo-tools /app
USER 10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-cp", "/app/classes:/app/lib/*"]
CMD ["lk.coopfed.knoweb.demo.DemoTillHistory"]
