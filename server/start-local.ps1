$ErrorActionPreference = 'Stop'
$localEnv = Join-Path $PSScriptRoot '.env.local'
if (-not (Test-Path -LiteralPath $localEnv)) {
    $localPassword = [guid]::NewGuid().ToString('N')
    [System.IO.File]::WriteAllText($localEnv, "POSTGRES_PASSWORD=$localPassword`n", [System.Text.UTF8Encoding]::new($false))
}
# TURN credentials stay out of the repo; load them if the operator provided them.
$secrets = Join-Path (Split-Path $PSScriptRoot -Parent) '.local.env'
if (Test-Path -LiteralPath $secrets) {
    foreach ($line in Get-Content -LiteralPath $secrets) {
        if ($line -match '^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$') {
            [System.Environment]::SetEnvironmentVariable($Matches[1], $Matches[2])
        }
    }
}
docker compose --project-name zisee-local --env-file $localEnv --file (Join-Path $PSScriptRoot 'compose.dev.yaml') up --build --detach
if ($LASTEXITCODE -ne 0) { throw 'Local backend failed to start.' }
Write-Output 'Local API: http://127.0.0.1:18080; credentials remain in ignored .env.local.'
