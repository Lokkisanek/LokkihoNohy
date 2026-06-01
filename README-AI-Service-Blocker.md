# Windows AI service blocker

This repository includes two PowerShell scripts for Windows classroom or local
PCs:

- `Block-AIServices.ps1` downloads the public laylavish AI blocklist and applies
  the block.
- `Unblock-AIServices.ps1` restores the machine state captured by the blocking
  script.

Run both scripts from an elevated PowerShell session.

## Why this does not use thousands of QoS policies

`New-NetQosPolicy -URIMatchCondition` is not a good fit for 3,300+ domain rules:

- it creates a very large number of policy objects when used one domain at a
  time;
- the cmdlet expects a single string for `-URIMatchCondition`, not a large array
  of domains;
- QoS throttling is not a reliable URL/domain blocking mechanism.

The blocking script uses a browser-enforced PAC file instead. The PAC file is a
local JavaScript proxy auto-config file that contains all downloaded domains and
blocks both exact domains and their subdomains by returning a dead local proxy
(`127.0.0.1:9`). Chrome and Edge are forced to use this PAC file through
enterprise registry policies, so the browser makes the block decision before DNS
resolution.

## What `Block-AIServices.ps1` changes

1. Downloads:
   `https://raw.githubusercontent.com/laylavish/uBlockOrigin-HUGE-AI-Blocklist/main/list.txt`
2. Extracts domains from uBlock/Adblock-style rules.
3. Writes these generated files under `C:\ProgramData\AIServiceBlocker`:
   - `domains.txt`
   - `ai-blocker.pac`
   - `state.json`
4. Sets Chrome and Edge machine policies under `HKLM:\SOFTWARE\Policies`:
   - `DnsOverHttpsMode = off`
   - `BuiltInDnsClientEnabled = 0`
   - `ProxyMode = pac_script`
   - `ProxyPacUrl = file:///C:/ProgramData/AIServiceBlocker/ai-blocker.pac`
5. Best-effort disables Windows DoH policy/settings.
6. Adds exact-domain entries to the Windows `hosts` file inside a managed marker
   block.
7. If Firefox is installed in Program Files, writes/merges enterprise
   `policies.json` to disable DoH and force the same PAC file.

The script stores previous registry values and Firefox policy file contents in
`state.json`, so `Unblock-AIServices.ps1` can restore them instead of blindly
deleting unrelated administrator policies.

## Usage

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\Block-AIServices.ps1
```

Then close and reopen Chrome, Edge, and Firefox so they reload enterprise
policies.

To remove the block:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\Unblock-AIServices.ps1
```

## Notes and limitations

- This is endpoint hardening, not a substitute for a network firewall or DNS
  resolver controlled by the school.
- Chrome and Edge can be governed reliably through machine policies on local PCs.
- Apps that ignore browser/system proxy settings may still need separate
  firewall, DNS, or application-control rules.
- If another administrator already manages browser proxy policies, review the
  change before deployment because this script intentionally forces a PAC file.
