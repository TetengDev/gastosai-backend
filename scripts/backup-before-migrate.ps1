<#
.SYNOPSIS
    Takes a full logical backup BEFORE Flyway applies anything. Fails closed.

.DESCRIPTION
    Windows counterpart of backup-before-migrate.sh, for the local dev machine. If the dump
    cannot be produced or looks truncated this throws, so the caller stops. A migration
    without a restorable backup is the one thing rollback cannot undo — an expand-contract
    step that drops a column takes the data with it.

    Reads DB_URL / DB_USERNAME / DB_PASSWORD (the same vars the app uses). DB_URL is the JDBC
    form, e.g. jdbc:postgresql://localhost:5433/gastos.

.EXAMPLE
    .\scripts\backup-before-migrate.ps1
    .\scripts\backup-before-migrate.ps1 -OutDir D:\backups

.NOTES
    Restore:
        psql "postgresql://user@host:port/db" -f <dump>.sql
#>
[CmdletBinding()]
param(
    [string]$OutDir = "backups"
)

$ErrorActionPreference = "Stop"

foreach ($name in @("DB_URL", "DB_USERNAME", "DB_PASSWORD")) {
    if (-not (Test-Path "env:$name")) {
        throw "$name is required."
    }
}

if (-not (Get-Command pg_dump -ErrorAction SilentlyContinue)) {
    throw "pg_dump not found. Install the PostgreSQL client tools and ensure they are on PATH."
}

# jdbc:postgresql://host:port/database?params
$uri = $env:DB_URL -replace '^jdbc:postgresql://', '' -replace '\?.*$', ''
if ($uri -notmatch '^(?<host>[^:/]+)(:(?<port>\d+))?/(?<database>.+)$') {
    throw "Could not parse DB_URL ('$($env:DB_URL)'). Expected jdbc:postgresql://host:port/database"
}
$dbHost = $Matches['host']
$port = if ($Matches['port']) { $Matches['port'] } else { "5432" }
$database = $Matches['database']

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
$target = Join-Path $OutDir "$database-pre-migrate-$stamp.sql"

Write-Host "Backing up $database at ${dbHost}:${port} -> $target"

$env:PGPASSWORD = $env:DB_PASSWORD
try {
    pg_dump --host=$dbHost --port=$port --username=$env:DB_USERNAME --dbname=$database `
        --no-owner --no-privileges --format=plain --file=$target
    if ($LASTEXITCODE -ne 0) {
        throw "pg_dump exited with $LASTEXITCODE — refusing to proceed with the migration."
    }
}
finally {
    Remove-Item env:PGPASSWORD -ErrorAction SilentlyContinue
}

# A dump that exists but is tiny means the connection succeeded and produced nothing useful.
$size = (Get-Item $target).Length
if ($size -lt 1024) {
    Remove-Item $target -Force
    throw "Backup is only $size bytes — refusing to proceed with the migration."
}

Write-Host "Backup OK ($size bytes): $target"
