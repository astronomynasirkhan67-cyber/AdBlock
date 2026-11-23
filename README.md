# NK Ad Block VPN

A native Android local-VPN DNS filtering starter project.

## Project identity
- App: **NK Ad Block VPN**
- Application ID: `com.nasir.nkadblockvpn`
- Version: `1.0.0`
- Suggested repository URL: `https://github.com/astronomynasirkhan67-cyber/NK-Ad-Block-VPN`
- Suggested project page URL (after enabling GitHub Pages): `https://astronomynasirkhan67-cyber.github.io/NK-Ad-Block-VPN/`

The suggested URLs become real only after you create/publish the repository and enable Pages.

## Build APK on GitHub
1. Create a GitHub repository named `NK-Ad-Block-VPN`.
2. Upload the contents of this ZIP to the repository root (not the ZIP file itself).
3. Open **Actions** and enable workflows if GitHub asks.
4. Select **Build NK Ad Block VPN APK** → **Run workflow**.
5. Open the completed workflow run → **Artifacts** → `NK-Ad-Block-VPN-debug`.
6. Download and extract the APK.

## Local build
Requires JDK 17 and Gradle 8.9+:
```bash
gradle assembleDebug
```

## Important limitations
This is an early DNS-filtering implementation, not a full commercial ad blocker. It blocks requests matching the small starter domain list. YouTube video ads and ads served from the same domains as app content cannot reliably be removed by DNS blocking. The implementation currently handles IPv4 UDP DNS requests; IPv6, TCP DNS, encrypted DNS/DoH inside apps, advanced DNS packet handling, per-app rules, and a large maintained blocklist are not included yet. Compatibility and battery/network behavior must be tested on real devices before public release.

The VPN is local on-device filtering, not an anonymizing VPN. Android normally permits only one active VPN at a time.
