# NK Ad Block VPN

Professional Android starter app for local VPN-based DNS filtering.

- Application ID: `com.nasir.nkadblockvpn`
- Version: `1.0.1`
- Launcher branding: custom NK shield mark
- Android foreground VPN service declares the Android 14+ `specialUse` foreground-service type.

## Build with GitHub Actions
1. Create a GitHub repository named `NK-Ad-Block-VPN`.
2. Upload the contents of this project folder to the repository root.
3. Open **Actions** and run **Build NK Ad Block VPN APK**.
4. Download `NK-Ad-Block-VPN-debug` from the run's Artifacts section.

## Important
This is an early DNS filtering prototype, not a production-certified ad blocker. It filters a small starter domain list and currently handles IPv4 UDP DNS. YouTube video ads and ads served from the same domains as app content cannot reliably be blocked by DNS. IPv6, TCP DNS, DNS-over-HTTPS inside apps, a maintained full blocklist, and robust production packet handling still require engineering and device testing. The VPN runs locally and does not provide anonymity or a remote VPN exit. Only one Android VPN can normally be active at a time.

The foreground-service manifest and runtime declaration are included to address Android 14+ foreground-service type requirements. If the VPN still stops on a particular device, collect its Logcat error (`NKAdBlockVPN` tag) for diagnosis.
