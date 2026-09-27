#!/bin/sh
# The worker image's Chromium as a command (K-06b), for the pipeline's integration tests:
# CHROMIUM_PATH points here, so A4RendererPostgresIntegrationTest prints with the browser the
# worker ships (infra/worker-image, coop-erp/worker-base:dev) rather than the runner's Google
# Chrome, which never exited after --print-to-pdf there. ChromiumPdf passes paths under the
# system temporary directory only, so that directory is mounted at the same place. No network.
set -e
tmp="${TMPDIR:-/tmp}"
case "$tmp" in /tmp/*) tmp=/tmp ;; esac
exec docker run --rm --network none --user "$(id -u):$(id -g)" \
    --env HOME="${HOME:-/tmp}" --volume "$tmp:$tmp" \
    --entrypoint /usr/bin/chromium coop-erp/worker-base:dev "$@"
