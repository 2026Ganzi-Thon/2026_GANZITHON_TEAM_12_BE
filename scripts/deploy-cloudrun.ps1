# Build the backend and ingress (nginx + frontend) images, push them to Artifact Registry,
# and deploy deploy/cloudrun/service.yaml to Cloud Run. See the deployment guide in docs/.
# This file stays ASCII-only so Windows PowerShell 5.1 reads it correctly without a BOM.
#
# Example:
#   .\scripts\deploy-cloudrun.ps1 -ProjectId my-project -DbHost 10.x.x.x -GoogleClientId xxx.apps.googleusercontent.com
#   .\scripts\deploy-cloudrun.ps1 ... -FrontendDist C:\path\to\frontend\dist -WithOpenAi
#   .\scripts\deploy-cloudrun.ps1 ... -RenderOnly   # only print the rendered service definition
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ProjectId,
    [Parameter(Mandatory = $true)][string]$DbHost,
    [Parameter(Mandatory = $true)][string]$GoogleClientId,
    [string]$Region = 'asia-northeast3',
    [string]$ServiceName = 'comedown',
    [string]$Repository = 'comedown',
    [string]$DbUser = 'comedown',
    [string]$ServiceAccount = '',
    [string]$FrontendDist = '',
    [string]$Tag = '',
    [string]$OpenAiModel = '',
    [switch]$WithOpenAi,
    [switch]$SkipBuild,
    [switch]$RenderOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

function Invoke-Native {
    param([string]$Exe, [string[]]$Arguments)
    Write-Host "> $Exe $($Arguments -join ' ')"
    & $Exe @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Exe failed with exit code $LASTEXITCODE"
    }
}

if (-not $ServiceAccount) {
    $ServiceAccount = "comedown-run@$ProjectId.iam.gserviceaccount.com"
}
if (-not $Tag) {
    $sha = (& git -C $root rev-parse --short HEAD).Trim()
    $Tag = "$sha-$(Get-Date -Format 'yyyyMMddHHmmss')"
}
if ($FrontendDist -and -not (Test-Path (Join-Path $FrontendDist 'index.html'))) {
    throw "FrontendDist must be a built frontend folder containing index.html: $FrontendDist"
}

$registry = "$Region-docker.pkg.dev/$ProjectId/$Repository"
$imageBackend = "$registry/backend:$Tag"
$imageIngress = "$registry/ingress:$Tag"

# Render the service definition. Values are not secrets, but they stay out of the repository.
$template = Get-Content -Raw -Encoding UTF8 (Join-Path $root 'deploy/cloudrun/service.yaml')
$values = [ordered]@{
    'SERVICE_NAME'     = $ServiceName
    'REGION'           = $Region
    'SERVICE_ACCOUNT'  = $ServiceAccount
    'IMAGE_INGRESS'    = $imageIngress
    'IMAGE_BACKEND'    = $imageBackend
    'DB_HOST'          = $DbHost
    'DB_USER'          = $DbUser
    'GOOGLE_CLIENT_ID' = $GoogleClientId
    'OPENAI_MODEL'     = $OpenAiModel
}
$rendered = $template
foreach ($key in $values.Keys) {
    $rendered = $rendered.Replace('${' + $key + '}', $values[$key])
}
if ($WithOpenAi) {
    $rendered = $rendered.Replace('#OPENAI_SECRET', '')
} else {
    $rendered = (($rendered -split "`n") | Where-Object { -not $_.StartsWith('#OPENAI_SECRET') }) -join "`n"
}
if ($rendered -match '\$\{[A-Z_]+\}') {
    throw "Unfilled placeholder in service.yaml: $($Matches[0])"
}

$renderedPath = Join-Path ([System.IO.Path]::GetTempPath()) "comedown-service-$Tag.yaml"
[System.IO.File]::WriteAllText($renderedPath, $rendered, (New-Object System.Text.UTF8Encoding($false)))

if ($RenderOnly) {
    Write-Host $rendered
    Write-Host "Rendered to $renderedPath"
    return
}

if (-not $SkipBuild) {
    Invoke-Native 'gcloud' @('auth', 'configure-docker', "$Region-docker.pkg.dev", '--quiet')

    Invoke-Native 'docker' @('build', '--platform', 'linux/amd64', '-t', $imageBackend, $root)

    $ingressArgs = @('build', '--platform', 'linux/amd64', '-t', $imageIngress)
    if ($FrontendDist) {
        $ingressArgs += @('--build-context', "site=$((Resolve-Path $FrontendDist).Path)")
    } else {
        Write-Host 'No -FrontendDist: the ingress image serves the placeholder page.'
    }
    $ingressArgs += (Join-Path $root 'deploy/cloudrun/ingress')
    Invoke-Native 'docker' $ingressArgs

    Invoke-Native 'docker' @('push', $imageBackend)
    Invoke-Native 'docker' @('push', $imageIngress)
}

Invoke-Native 'gcloud' @('run', 'services', 'replace', $renderedPath, '--project', $ProjectId, '--region', $Region)

# Public HTTPS entry point. App-level login is still enforced by Spring Security.
Invoke-Native 'gcloud' @('run', 'services', 'add-iam-policy-binding', $ServiceName,
    '--project', $ProjectId, '--region', $Region,
    '--member', 'allUsers', '--role', 'roles/run.invoker', '--quiet')

$url = (& gcloud run services describe $ServiceName --project $ProjectId --region $Region --format 'value(status.url)').Trim()
Remove-Item $renderedPath -ErrorAction SilentlyContinue
Write-Host ''
Write-Host "Deployed $Tag"
Write-Host "URL: $url"
Write-Host "Google OAuth redirect URIs to register:"
Write-Host "  $url/login/oauth2/code/google"
Write-Host "  $url/login/oauth2/code/google-calendar"
