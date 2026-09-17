git diff --exit-code web/src/generated

if ($LASTEXITCODE -ne 0) {
    Write-Error "Generated TypeScript clients in web/src/generated are out of sync with the OpenAPI specs. Please run tools/gen-clients.ps1 and commit the changes."
    exit $LASTEXITCODE
}
