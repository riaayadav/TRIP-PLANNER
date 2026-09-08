param(
    [switch]$SkipAdminSetup,
    [switch]$UseMySql
)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$mavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
$bundledMaven = Join-Path $PSScriptRoot 'tools/apache-maven-3.9.11/bin/mvn.cmd'
if ($mavenCommand) {
    $mavenPath = $mavenCommand.Source
}
elseif (Test-Path -LiteralPath $bundledMaven) {
    $mavenPath = $bundledMaven
}
else {
    throw 'Install Maven 3.9+ and add its bin folder to PATH, then run this script again.'
}
if (-not (Get-Command java.exe -ErrorAction SilentlyContinue)) {
    throw 'Install JDK 17+ and set JAVA_HOME/PATH first.'
}
$oldEmail = $env:ADMIN_EMAIL
$oldPassword = $env:ADMIN_PASSWORD
$oldLocalDatabase = $env:LOCAL_DATABASE
$oldDbUrl = $env:DB_URL
$oldDbUser = $env:DB_USER
$oldDbPassword = $env:DB_PASSWORD
try {
    $env:LOCAL_DATABASE = if ($UseMySql) { 'mysql' } else { 'h2' }
    if ($UseMySql) {
        if (-not $env:DB_URL) {
            $env:DB_URL = Read-Host 'MySQL JDBC URL (jdbc:mysql://host:port/database)'
        }
        if (-not $env:DB_URL.StartsWith('jdbc:mysql://')) {
            throw 'DB_URL must start with jdbc:mysql://'
        }
        if (-not $env:DB_USER) {
            $env:DB_USER = Read-Host 'MySQL user'
        }
        if (-not $env:DB_USER) {
            throw 'MySQL user is required.'
        }
        if ($null -eq $env:DB_PASSWORD) {
            $secureDbPassword = Read-Host 'MySQL password' -AsSecureString
            $env:DB_PASSWORD = [System.Net.NetworkCredential]::new('', $secureDbPassword).Password
        }
        Write-Host 'Using the configured MySQL database. First startup initializes its application schema unless DB_INITIALIZE=false.'
    }
    if (-not $SkipAdminSetup) {
        Write-Host 'Local preview only. Existing administrator passwords will not be reset.'
        $env:ADMIN_EMAIL = Read-Host 'Administrator email (leave blank to start without creating one)'
        if ($env:ADMIN_EMAIL) {
            if ($env:ADMIN_EMAIL -notmatch '^[^\s@]+@[^\s@]+\.[^\s@]+$') {
                throw 'Enter a valid administrator email.'
            }
            $securePassword = Read-Host 'Administrator password (12-128 characters)' -AsSecureString
            $env:ADMIN_PASSWORD = [System.Net.NetworkCredential]::new('', $securePassword).Password
            if ($env:ADMIN_PASSWORD.Length -lt 12 -or $env:ADMIN_PASSWORD.Length -gt 128) {
                throw 'Password must have 12-128 characters.'
            }
        }
    }
    Write-Host 'Starting http://127.0.0.1:8080. H2 data is in .local-data; MySQL mode uses its configured database. Stop with Ctrl+C.'
    $mavenArgs = @('-B')
    if (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'tools/m2')) {
        $mavenArgs += "-Dmaven.repo.local=$PSScriptRoot/tools/m2"
    }
    & $mavenPath @mavenArgs test-compile exec:java '-Dexec.mainClass=com.tripplanner.DevServer' '-Dexec.classpathScope=test'
    if ($LASTEXITCODE -ne 0) {
        throw 'Startup failed. Review the error above; ensure port 8080 is available.'
    }
} finally {
    $env:ADMIN_EMAIL = $oldEmail
    $env:ADMIN_PASSWORD = $oldPassword
    $env:LOCAL_DATABASE = $oldLocalDatabase
    $env:DB_URL = $oldDbUrl
    $env:DB_USER = $oldDbUser
    $env:DB_PASSWORD = $oldDbPassword
    if ($securePassword) {
        $securePassword.Dispose()
    }
    if ($secureDbPassword) {
        $secureDbPassword.Dispose()
    }
}
