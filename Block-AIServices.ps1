#requires -version 5.1
<#
.SYNOPSIS
Blocks known AI services on Windows browsers and the local resolver.

.DESCRIPTION
Downloads the laylavish uBlock Origin AI blocklist, extracts domains, writes a
local PAC file that blocks those domains and their subdomains, forces Chrome and
Edge to use that PAC file, disables browser DoH, updates the hosts file for the
exact domains, and flushes DNS/proxy caches.

Run from an elevated PowerShell session.
#>

[CmdletBinding()]
param(
    [string]$BlocklistUrl = "https://raw.githubusercontent.com/laylavish/uBlockOrigin-HUGE-AI-Blocklist/main/list.txt",
    [string]$InstallDir = "$env:ProgramData\AIServiceBlocker",
    [switch]$SkipHosts,
    [switch]$SkipFirefox,
    [switch]$KeepBrowsersOpen
)

$ErrorActionPreference = "Stop"

function Assert-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw "Tento skript MUSIS spustit jako Administrator."
    }
}

function ConvertTo-FileUri {
    param([Parameter(Mandatory)][string]$Path)

    $fullPath = [IO.Path]::GetFullPath($Path)
    return ([Uri]::new($fullPath)).AbsoluteUri
}

function ConvertTo-DomainCandidate {
    param([AllowEmptyString()][string]$Text)

    $candidate = $Text.Trim().ToLowerInvariant()
    if (-not $candidate) { return $null }

    $candidate = $candidate -replace '^https?://', ''
    $candidate = $candidate -replace '^//', ''
    $candidate = $candidate -replace '^\|https?://', ''
    $candidate = $candidate -replace '^\|\|', ''
    $candidate = $candidate -replace '^[*.]+', ''
    $candidate = ($candidate -split '[/\^:*?#[\]\*]', 2)[0]
    $candidate = $candidate.Trim('.- ')

    if ($candidate -match '^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z][a-z0-9-]{1,62}$') {
        return $candidate
    }

    return $null
}

function Get-DomainsFromRule {
    param([AllowEmptyString()][string]$Rule)

    $line = $Rule.Trim().ToLowerInvariant()
    if (-not $line) { return @() }
    if ($line.StartsWith("!") -or $line.StartsWith("#") -or $line.StartsWith("[")) { return @() }
    if ($line.StartsWith("@@")) { return @() }
    if ($line.StartsWith("/") -and $line.EndsWith("/")) { return @() }

    $domains = New-Object 'System.Collections.Generic.List[string]'
    $isCosmeticRule = $line -match '##|#@#|#\?#'
    $searchText = $line

    if ($isCosmeticRule) {
        # The left side is usually the site where the cosmetic rule runs
        # (for example duckduckgo.com,bing.com). The AI target is in the selector.
        $parts = [regex]::Split($line, '##|#@#|#\?#', 2)
        if ($parts.Count -gt 1) {
            $searchText = $parts[1]
        }

        $attributePattern = '(?i)(?:href|src)[^"'']*["''](?<value>[^"'']+)["'']'
        foreach ($match in [regex]::Matches($searchText, $attributePattern)) {
            $domain = ConvertTo-DomainCandidate -Text $match.Groups['value'].Value
            if ($domain) { $domains.Add($domain) | Out-Null }
        }
    } else {
        # Accept hosts-file style rows as well as uBlock/Adblock network rules.
        if ($searchText -match '^(?:0\.0\.0\.0|127\.0\.0\.1|::1?)\s+(.+)$') {
            $searchText = $Matches[1]
        }

        $searchText = ($searchText -split '\$', 2)[0]
        $domain = ConvertTo-DomainCandidate -Text $searchText
        if ($domain) { $domains.Add($domain) | Out-Null }
    }

    return $domains.ToArray()
}

function Get-BlockDomains {
    param([Parameter(Mandatory)][string]$Url)

    Write-Host "1. Stahuji AI blocklist..." -ForegroundColor Cyan
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $rawText = Invoke-WebRequest -Uri $Url -UseBasicParsing | Select-Object -ExpandProperty Content

    $domains = [System.Collections.Generic.HashSet[string]]::new()
    foreach ($line in ($rawText -split "\r?\n")) {
        if ([string]::IsNullOrWhiteSpace($line)) {
            continue
        }

        foreach ($domain in (Get-DomainsFromRule -Rule $line)) {
            if ($domain) {
                [void]$domains.Add($domain)
            }
        }
    }

    # Extra high-value endpoints that are easy to miss in browser-focused lists.
    $extraDomains = @(
        "api.anthropic.com",
        "api.cohere.ai",
        "api.deepseek.com",
        "api.githubcopilot.com",
        "api.groq.com",
        "api.mistral.ai",
        "api.openai.com",
        "chat.openai.com",
        "claude.ai",
        "copilot.microsoft.com",
        "gemini.google.com",
        "generativelanguage.googleapis.com",
        "openai.com",
        "perplexity.ai"
    )
    foreach ($domain in $extraDomains) {
        [void]$domains.Add($domain)
    }

    $result = @($domains) | Sort-Object
    if (-not $result -or $result.Count -lt 100) {
        throw "Blocklist obsahuje neocekavane malo domen ($($result.Count))."
    }

    Write-Host "   -> Nacteno $($result.Count) unikatnich domen." -ForegroundColor Green
    return $result
}

function New-BlockPacFile {
    param(
        [Parameter(Mandatory)][string[]]$Domains,
        [Parameter(Mandatory)][string]$Path
    )

    $domainLines = foreach ($domain in $Domains) {
        "  `"$domain`": 1"
    }

    $pac = @"
// Generated by Block-AIServices.ps1. Do not edit by hand.
var AI_BLOCKED_DOMAINS = {
$($domainLines -join ",`r`n")
};

function normalizeHost(host) {
  if (!host) {
    return "";
  }

  host = host.toLowerCase();
  if (host.charAt(host.length - 1) === ".") {
    host = host.substring(0, host.length - 1);
  }

  return host;
}

function isBlockedAiDomain(host) {
  host = normalizeHost(host);
  if (!host) {
    return false;
  }

  if (AI_BLOCKED_DOMAINS[host]) {
    return true;
  }

  var labels = host.split(".");
  for (var i = 1; i < labels.length - 1; i++) {
    var suffix = labels.slice(i).join(".");
    if (AI_BLOCKED_DOMAINS[suffix]) {
      return true;
    }
  }

  return false;
}

function isGoogleSearchHost(host) {
  host = normalizeHost(host);
  return host === "google.com" || host === "www.google.com" ||
         shExpMatch(host, "google.*") || shExpMatch(host, "www.google.*");
}

function isGoogleSearchWithoutWebMode(url, host) {
  if (!url || !isGoogleSearchHost(host)) {
    return false;
  }

  var normalizedUrl = url.toLowerCase();
  if (normalizedUrl.indexOf("/search?") === -1 && normalizedUrl.indexOf("/search#") === -1) {
    return false;
  }

  return normalizedUrl.indexOf("udm=14") === -1 && normalizedUrl.indexOf("udm%3d14") === -1;
}

function FindProxyForURL(url, host) {
  if (isPlainHostName(host) || isInNet(host, "10.0.0.0", "255.0.0.0") ||
      isInNet(host, "172.16.0.0", "255.240.0.0") ||
      isInNet(host, "192.168.0.0", "255.255.0.0") ||
      isInNet(host, "127.0.0.0", "255.0.0.0")) {
    return "DIRECT";
  }

  if (isGoogleSearchWithoutWebMode(url, host) || isBlockedAiDomain(host)) {
    return "PROXY 127.0.0.1:9";
  }

  return "DIRECT";
}
"@

    Set-Content -Path $Path -Value $pac -Encoding ASCII
}

function ConvertTo-JsonFile {
    param(
        [Parameter(Mandatory)]$InputObject,
        [Parameter(Mandatory)][string]$Path
    )

    $json = $InputObject | ConvertTo-Json -Depth 20
    Set-Content -Path $Path -Value $json -Encoding UTF8
}

function Import-State {
    param([Parameter(Mandatory)][string]$Path)

    if (Test-Path -Path $Path) {
        return Get-Content -Path $Path -Raw | ConvertFrom-Json
    }

    return [pscustomobject]@{
        RegistryValues = @()
        FirefoxPolicies = @()
    }
}

function Test-StateHasRegistryValue {
    param(
        [Parameter(Mandatory)]$State,
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Name
    )

    foreach ($entry in @($State.RegistryValues)) {
        if ($entry.Path -eq $Path -and $entry.Name -eq $Name) {
            return $true
        }
    }

    return $false
}

function Backup-RegistryValue {
    param(
        [Parameter(Mandatory)]$State,
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Name
    )

    if (Test-StateHasRegistryValue -State $State -Path $Path -Name $Name) {
        return
    }

    $exists = $false
    $kind = $null
    $value = $null

    if (Test-Path -Path $Path) {
        $key = Get-Item -Path $Path
        $valueNames = @($key.GetValueNames())
        if ($valueNames -contains $Name) {
            $exists = $true
            $kind = $key.GetValueKind($Name).ToString()
            $value = $key.GetValue($Name, $null, "DoNotExpandEnvironmentNames")
        }
    }

    $State.RegistryValues += [pscustomobject]@{
        Path   = $Path
        Name   = $Name
        Exists = $exists
        Kind   = $kind
        Value  = $value
    }
}

function Set-PolicyRegistryValue {
    param(
        [Parameter(Mandatory)]$State,
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)]$Value,
        [Parameter(Mandatory)][ValidateSet("String", "DWord")][string]$Type
    )

    Backup-RegistryValue -State $State -Path $Path -Name $Name
    New-Item -Path $Path -Force | Out-Null
    New-ItemProperty -Path $Path -Name $Name -Value $Value -PropertyType $Type -Force | Out-Null
}

function Set-PolicyRegistryList {
    param(
        [Parameter(Mandatory)]$State,
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string[]]$Values
    )

    New-Item -Path $Path -Force | Out-Null

    if (Test-Path -Path $Path) {
        $existingNames = @((Get-Item -Path $Path).GetValueNames())
        foreach ($existingName in $existingNames) {
            Backup-RegistryValue -State $State -Path $Path -Name $existingName
            Remove-ItemProperty -Path $Path -Name $existingName -ErrorAction SilentlyContinue
        }
    }

    for ($i = 0; $i -lt $Values.Count; $i++) {
        $name = [string]($i + 1)
        Set-PolicyRegistryValue -State $State -Path $Path -Name $name -Value $Values[$i] -Type "String"
    }
}

function Get-GoogleSearchPolicyPatterns {
    param([Parameter(Mandatory)][ValidateSet("Block", "Allow")][string]$Mode)

    $hosts = @(
        "google.com",
        "www.google.com",
        "google.cz",
        "www.google.cz",
        "google.sk",
        "www.google.sk",
        "google.de",
        "www.google.de",
        "google.at",
        "www.google.at",
        "google.co.uk",
        "www.google.co.uk"
    )

    $schemes = @("https://", "http://", "")
    $patterns = New-Object 'System.Collections.Generic.List[string]'

    foreach ($host in $hosts) {
        foreach ($scheme in $schemes) {
            if ($Mode -eq "Allow") {
                $patterns.Add("$scheme$host/search@udm=14") | Out-Null
            } else {
                $patterns.Add("$scheme$host/search") | Out-Null
                $patterns.Add("$scheme$host/search@q=*") | Out-Null
            }
        }
    }

    return $patterns.ToArray()
}

function Set-BrowserPolicies {
    param(
        [Parameter(Mandatory)]$State,
        [Parameter(Mandatory)][string]$PacUri
    )

    Write-Host "3. Vynucuji Chrome/Edge policy a vypinam DoH..." -ForegroundColor Cyan
    $browserPolicyPaths = @(
        "HKLM:\SOFTWARE\Policies\Google\Chrome",
        "HKCU:\SOFTWARE\Policies\Google\Chrome",
        "HKLM:\SOFTWARE\Policies\Microsoft\Edge",
        "HKCU:\SOFTWARE\Policies\Microsoft\Edge"
    )

    foreach ($path in $browserPolicyPaths) {
        Set-PolicyRegistryValue -State $State -Path $path -Name "DnsOverHttpsMode" -Value "off" -Type "String"
        Set-PolicyRegistryValue -State $State -Path $path -Name "BuiltInDnsClientEnabled" -Value 0 -Type "DWord"
        Set-PolicyRegistryValue -State $State -Path $path -Name "ProxyMode" -Value "pac_script" -Type "String"
        Set-PolicyRegistryValue -State $State -Path $path -Name "ProxyPacUrl" -Value $PacUri -Type "String"
        Set-PolicyRegistryValue -State $State -Path $path -Name "PacHttpsUrlStrippingEnabled" -Value 0 -Type "DWord"
        Set-PolicyRegistryValue -State $State -Path $path -Name "DefaultSearchProviderEnabled" -Value 1 -Type "DWord"
        Set-PolicyRegistryValue -State $State -Path $path -Name "DefaultSearchProviderName" -Value "Google Web" -Type "String"
        Set-PolicyRegistryValue -State $State -Path $path -Name "DefaultSearchProviderKeyword" -Value "google-web" -Type "String"
        Set-PolicyRegistryValue -State $State -Path $path -Name "DefaultSearchProviderSearchURL" -Value "https://www.google.com/search?q={searchTerms}&udm=14" -Type "String"

        $googleSearchBlockPatterns = Get-GoogleSearchPolicyPatterns -Mode "Block"
        $googleSearchAllowPatterns = Get-GoogleSearchPolicyPatterns -Mode "Allow"
        foreach ($blocklistPolicyName in @("URLBlocklist", "URLBlacklist")) {
            Set-PolicyRegistryList -State $State -Path (Join-Path -Path $path -ChildPath $blocklistPolicyName) -Values $googleSearchBlockPatterns
        }
        foreach ($allowlistPolicyName in @("URLAllowlist", "URLWhitelist")) {
            Set-PolicyRegistryList -State $State -Path (Join-Path -Path $path -ChildPath $allowlistPolicyName) -Values $googleSearchAllowPatterns
        }
    }
}

function Disable-WindowsDohBestEffort {
    param([Parameter(Mandatory)]$State)

    Write-Host "4. Vypinam Windows DoH, pokud je v systemu podporovano..." -ForegroundColor Cyan
    $dnsPolicyPath = "HKLM:\SOFTWARE\Policies\Microsoft\Windows NT\DNSClient"
    Set-PolicyRegistryValue -State $State -Path $dnsPolicyPath -Name "DoHPolicy" -Value 1 -Type "DWord"

    $dnsCachePath = "HKLM:\SYSTEM\CurrentControlSet\Services\Dnscache\Parameters"
    Set-PolicyRegistryValue -State $State -Path $dnsCachePath -Name "EnableAutoDoh" -Value 0 -Type "DWord"

    try {
        & netsh dns set global doh=no | Out-Null
    } catch {
        Write-Verbose "netsh dns set global doh=no selhalo nebo neni v teto verzi Windows dostupne: $($_.Exception.Message)"
    }
}

function Remove-ManagedHostsBlock {
    param([Parameter(Mandatory)][string]$HostsPath)

    if (-not (Test-Path -Path $HostsPath)) {
        return ""
    }

    $content = Get-Content -Path $HostsPath -Raw
    $pattern = "(?ms)^# BEGIN AI_SERVICE_BLOCKER\r?\n.*?^# END AI_SERVICE_BLOCKER\r?\n?"
    return [regex]::Replace($content, $pattern, "").TrimEnd()
}

function Set-HostsBlock {
    param([Parameter(Mandatory)][string[]]$Domains)

    if ($SkipHosts) {
        Write-Host "5. Preskakuji hosts podle parametru -SkipHosts." -ForegroundColor Yellow
        return
    }

    Write-Host "5. Aktualizuji hosts pro presne domeny..." -ForegroundColor Cyan
    $hostsPath = "$env:SystemRoot\System32\drivers\etc\hosts"
    $baseContent = Remove-ManagedHostsBlock -HostsPath $hostsPath
    $entries = foreach ($domain in $Domains) {
        "0.0.0.0 $domain"
        ":: $domain"
    }

    $newBlock = @(
        "# BEGIN AI_SERVICE_BLOCKER"
        "# Generated by Block-AIServices.ps1. Subdomains are covered by the PAC browser policy."
        $entries
        "# END AI_SERVICE_BLOCKER"
    ) -join "`r`n"

    $combined = if ($baseContent) {
        $baseContent + "`r`n`r`n" + $newBlock + "`r`n"
    } else {
        $newBlock + "`r`n"
    }

    Set-Content -Path $hostsPath -Value $combined -Encoding ASCII
}

function Test-StateHasFirefoxPolicy {
    param(
        [Parameter(Mandatory)]$State,
        [Parameter(Mandatory)][string]$Path
    )

    foreach ($entry in @($State.FirefoxPolicies)) {
        if ($entry.Path -eq $Path) {
            return $true
        }
    }

    return $false
}

function Set-JsonProperty {
    param(
        [Parameter(Mandatory)]$Object,
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)]$Value
    )

    if ($Object.PSObject.Properties.Name -contains $Name) {
        $Object.$Name = $Value
    } else {
        $Object | Add-Member -NotePropertyName $Name -NotePropertyValue $Value
    }
}

function Set-FirefoxPolicyFile {
    param(
        [Parameter(Mandatory)]$State,
        [Parameter(Mandatory)][string]$PacUri
    )

    if ($SkipFirefox) {
        Write-Host "6. Preskakuji Firefox podle parametru -SkipFirefox." -ForegroundColor Yellow
        return
    }

    Write-Host "6. Nastavuji Firefox enterprise policies, pokud je Firefox nainstalovan..." -ForegroundColor Cyan
    $candidateRoots = @(
        "$env:ProgramFiles\Mozilla Firefox",
        "${env:ProgramFiles(x86)}\Mozilla Firefox"
    ) | Where-Object { $_ -and (Test-Path -Path $_) }

    foreach ($root in $candidateRoots) {
        $distributionDir = Join-Path -Path $root -ChildPath "distribution"
        $policyPath = Join-Path -Path $distributionDir -ChildPath "policies.json"

        if (-not (Test-StateHasFirefoxPolicy -State $State -Path $policyPath)) {
            $State.FirefoxPolicies += [pscustomobject]@{
                Path    = $policyPath
                Exists  = (Test-Path -Path $policyPath)
                Content = if (Test-Path -Path $policyPath) { Get-Content -Path $policyPath -Raw } else { $null }
            }
        }

        New-Item -Path $distributionDir -ItemType Directory -Force | Out-Null

        if (Test-Path -Path $policyPath) {
            $policyRoot = Get-Content -Path $policyPath -Raw | ConvertFrom-Json
        } else {
            $policyRoot = [pscustomobject]@{}
        }

        if (-not ($policyRoot.PSObject.Properties.Name -contains "policies")) {
            $policyRoot | Add-Member -NotePropertyName "policies" -NotePropertyValue ([pscustomobject]@{})
        }

        Set-JsonProperty -Object $policyRoot.policies -Name "DNSOverHTTPS" -Value ([pscustomobject]@{
            Enabled = $false
            Locked  = $true
        })
        Set-JsonProperty -Object $policyRoot.policies -Name "Proxy" -Value ([pscustomobject]@{
            Mode          = "autoConfig"
            AutoConfigURL = $PacUri
            Locked        = $true
        })

        ConvertTo-JsonFile -InputObject $policyRoot -Path $policyPath
    }
}

function Stop-BrowserProcessesForPolicyReload {
    if ($KeepBrowsersOpen) {
        Write-Host "8. Prohlizece nechavam bezet podle parametru -KeepBrowsersOpen." -ForegroundColor Yellow
        return
    }

    Write-Host "8. Zaviram Chrome/Edge, aby nacetly nove enterprise policies..." -ForegroundColor Cyan
    foreach ($processName in @("chrome", "msedge")) {
        Get-Process -Name $processName -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    }
}

function Invoke-CacheRefresh {
    Write-Host "7. Obnovuji DNS/proxy cache..." -ForegroundColor Cyan
    try { & ipconfig /flushdns | Out-Null } catch { Write-Verbose $_.Exception.Message }
}

Assert-Administrator

New-Item -Path $InstallDir -ItemType Directory -Force | Out-Null
$statePath = Join-Path -Path $InstallDir -ChildPath "state.json"
$domainPath = Join-Path -Path $InstallDir -ChildPath "domains.txt"
$pacPath = Join-Path -Path $InstallDir -ChildPath "ai-blocker.pac"

$state = Import-State -Path $statePath
$domains = Get-BlockDomains -Url $BlocklistUrl

Write-Host "2. Generuji PAC soubor s blokaci domen i subdomen..." -ForegroundColor Cyan
Set-Content -Path $domainPath -Value ($domains -join "`r`n") -Encoding ASCII
New-BlockPacFile -Domains $domains -Path $pacPath
$pacUri = ConvertTo-FileUri -Path $pacPath

Set-BrowserPolicies -State $state -PacUri $pacUri
Disable-WindowsDohBestEffort -State $state
Set-HostsBlock -Domains $domains
Set-FirefoxPolicyFile -State $state -PacUri $pacUri

$state | Add-Member -NotePropertyName "BlocklistUrl" -NotePropertyValue $BlocklistUrl -Force
$state | Add-Member -NotePropertyName "InstallDir" -NotePropertyValue $InstallDir -Force
$state | Add-Member -NotePropertyName "PacUri" -NotePropertyValue $pacUri -Force
$state | Add-Member -NotePropertyName "DomainsCount" -NotePropertyValue $domains.Count -Force
$state | Add-Member -NotePropertyName "UpdatedAt" -NotePropertyValue ([DateTimeOffset]::Now.ToString("o")) -Force
ConvertTo-JsonFile -InputObject $state -Path $statePath

Invoke-CacheRefresh
Stop-BrowserProcessesForPolicyReload

Write-Host ""
Write-Host "Hotovo. Blokace je aplikovana pro Chrome/Edge pres vynuceny PAC, DoH je vypnute policy a hosts obsahuje presne domeny." -ForegroundColor Green
Write-Host "Znovu otevri prohlizec a zkontroluj chrome://policy nebo edge://policy." -ForegroundColor Yellow
