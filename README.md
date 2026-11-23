# NK Ad Block VPN — improved DNS filtering prototype

Android local-VPN DNS filtering app.

- Application ID: `com.nasir.nkadblockvpn`
- Version: 1.0.1
- Local VPN DNS filtering plus a small built-in domain denylist
- Uses AdGuard public DNS (`94.140.14.14`) as its upstream resolver
- Explicit VPN stop action closes the TUN descriptor and removes the foreground notification
- Android 14+ `specialUse` foreground service declaration

## GitHub Actions
Upload the contents of this folder to the repository root, commit, then run **Build NK Ad Block VPN APK** in Actions. Download the APK from the workflow Artifacts.

## Important limitations
This is still a prototype. DNS filtering can block domains known to the DNS provider or local denylist, but it cannot reliably remove ads served from the same domains as app content, ads delivered through app-specific encrypted DNS/DoH, cached ads, or every in-app ad SDK. The built-in list is not a comprehensive maintained list. YouTube video ads and many social-media ads may remain. The VPN does not anonymize traffic; it only filters DNS. Only one VPN can normally be active at a time.

This code handles IPv4 UDP DNS only. IPv6 DNS, TCP DNS, app-level DoH, and production-grade packet handling are not implemented. Test carefully; if internet access fails, turn protection off and check device logs.
