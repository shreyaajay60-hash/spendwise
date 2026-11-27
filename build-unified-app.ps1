# SpendWise Unified Build Script
# Builds React frontend and bundles it into Spring Boot executable JAR

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$frontendDir = Resolve-Path "$scriptDir\..\spendwise-frontend"
$backendDir = $scriptDir
$staticDir = "$backendDir\src\main\resources\static"

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host " Building SpendWise Unified Application" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan

# 1. Build React frontend
Write-Host "`n[1/3] Building React frontend (Vite)..." -ForegroundColor Yellow
Set-Location $frontendDir
npm run build

# 2. Sync to Spring Boot static resources
Write-Host "`n[2/3] Bundling static assets into Spring Boot..." -ForegroundColor Yellow
if (Test-Path $staticDir) {
    Remove-Item -Path $staticDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $staticDir | Out-Null
Copy-Item -Path "$frontendDir\dist\*" -Destination $staticDir -Recurse -Force

# 3. Package Spring Boot executable JAR
Write-Host "`n[3/3] Packaging executable Spring Boot JAR..." -ForegroundColor Yellow
Set-Location $backendDir
.\mvnw.cmd clean package -DskipTests

$jarPath = "$backendDir\target\spendwise-backend-1.0.0.jar"
if (Test-Path $jarPath) {
    $sizeMb = [math]::Round((Get-Item $jarPath).Length / 1MB, 2)
    Write-Host "`n=========================================" -ForegroundColor Green
    Write-Host " BUILD COMPLETE: $jarPath ($sizeMb MB)" -ForegroundColor Green
    Write-Host " Run with: java -jar target\spendwise-backend-1.0.0.jar" -ForegroundColor Green
    Write-Host "=========================================" -ForegroundColor Green
}
