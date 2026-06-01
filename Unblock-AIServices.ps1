#requires -version 5.1
<#
.SYNOPSIS
Removes blocks created by Block-AIServices.ps1.

.DESCRIPTION
Restores registry/browser policy values and Firefox policy files from the state
captured during blocking, removes the managed hosts section, deletes generated
PAC/domain files, and flushes DNS/proxy caches.

Run from an elevated PowerShell session.
#>

[CmdletBinding()]
param(
    [string]$InstallDir = "$env:ProgramData\AIServiceBlocker"
)

$ErrorActionPreference = "Stop"

function Assert-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw "Tento skript MUSIS spustit jako Administrator."
    }
}

function Remove-ManagedHostsBlock {
    $hostsPath = "$env:SystemRoot\System32\drivers\etc\hosts"
    if (-not (Test-Path -Path $hostsPath)) {
        return
    }

    $content = Get-Content -Path $hostsPath -Raw
    $pattern = "(?ms)^# BEGIN AI_SERVICE_BLOCKER\r?\n.*?^# END AI_SERVICE_BLOCKER\r?\n?"
    $cleaned = [regex]::Replace($content, $pattern, "").TrimEnd()

    if ($cleaned) {
        Set-Content -Path $hostsPath -Value ($cleaned + "`r`n") -Encoding ASCII
    } else {
        Set-Content -Path $hostsPath -Value "" -Encoding ASCII
    }
}

function Restore-RegistryValue {
    param([Parameter(Mandatory)]$Entry)

    $path = [string]$Entry.Path
    $name = [string]$Entry.Name

    if ([bool]$Entry.Exists) {
        New-Item -Path $path -Force | Out-Null
        New-ItemProperty -Path $path -Name $name -Value $Entry.Value -PropertyType ([string]$Entry.Kind) -Force | Out-Null
    } elseif (Test-Path -Path $path) {
        Remove-ItemProperty -Path $path -Name $name -ErrorAction SilentlyContinue
    }
}

function Restore-FirefoxPolicy {
    param([Parameter(Mandatory)]$Entry)

    $path = [string]$Entry.Path
    $directory = Split-Path -Path $path -Parent

    if ([bool]$Entry.Exists) {
        New-Item -Path $directory -ItemType Directory -Force | Out-Null
        Set-Content -Path $path -Value ([string]$Entry.Content) -Encoding UTF8
    } elseif (Test-Path -Path $path) {
        Remove-Item -Path $path -Force

        try {
            if ((Test-Path -Path $directory) -and -not (Get-ChildItem -Path $directory -Force -ErrorAction SilentlyContinue)) {
                Remove-Item -Path $directory -Force
            }
        } catch {
            Write-Verbose "Nepodarilo se odstranit prazdny adresar Firefox distribution: $($_.Exception.Message)"
        }
    }
}

function Invoke-CacheRefresh {
    try { & ipconfig /flushdns | Out-Null } catch { Write-Verbose $_.Exception.Message }
}

Assert-Administrator

$statePath = Join-Path -Path $InstallDir -ChildPath "state.json"
$domainPath = Join-Path -Path $InstallDir -ChildPath "domains.txt"
$pacPath = Join-Path -Path $InstallDir -ChildPath "ai-blocker.pac"

Write-Host "1. Odstranuji spravovany blok v hosts..." -ForegroundColor Cyan
Remove-ManagedHostsBlock

if (Test-Path -Path $statePath) {
    Write-Host "2. Obnovuji registry a browser policies ze state.json..." -ForegroundColor Cyan
    $state = Get-Content -Path $statePath -Raw | ConvertFrom-Json

    foreach ($entry in @($state.RegistryValues)) {
        Restore-RegistryValue -Entry $entry
    }

    Write-Host "3. Obnovuji Firefox policies..." -ForegroundColor Cyan
    foreach ($entry in @($state.FirefoxPolicies)) {
        Restore-FirefoxPolicy -Entry $entry
    }
} else {
    Write-Warning "Nenalezen $statePath. Odstranil jsem jen hosts blok a generovane soubory; registry/policies nelze bez state.json bezpecne obnovit."
}

Write-Host "4. Mazu generovane soubory..." -ForegroundColor Cyan
foreach ($path in @($domainPath, $pacPath, $statePath)) {
    if (Test-Path -Path $path) {
        Remove-Item -Path $path -Force
    }
}

try {
    if ((Test-Path -Path $InstallDir) -and -not (Get-ChildItem -Path $InstallDir -Force -ErrorAction SilentlyContinue)) {
        Remove-Item -Path $InstallDir -Force
    }
} catch {
    Write-Verbose "Nepodarilo se odstranit instalacni adresar: $($_.Exception.Message)"
}

Write-Host "5. Obnovuji DNS/proxy cache..." -ForegroundColor Cyan
Invoke-CacheRefresh

Write-Host ""
Write-Host "Odblokovano. Zavrete a znovu spustte prohlizece, aby znovu nacetly policies." -ForegroundColor Green
