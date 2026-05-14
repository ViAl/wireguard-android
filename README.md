# WireGuard Android — Jail Edition

A modified Android GUI for [WireGuard](https://www.wireguard.com/), based on the official app.  
This fork adds three feature areas designed for users who need more control over app-level network routing and app isolation on Android.

> ⚠️ This is a fork. Upstream improvements are periodically merged. The core WireGuard tunnel functionality remains unchanged.

---

## Added Features

### 1. Per-App Split Tunneling

Fine-grained control over which apps are routed through a WireGuard tunnel:

- **Three routing modes** per tunnel: `ALL_APPLICATIONS` (default), `INCLUDE_ONLY_SELECTED_APPLICATIONS`, and `EXCLUDE_SELECTED_APPLICATIONS`.
- **Jail-managed routing** via the `PerAppVpnManager`: select apps in the Jail tab and assign them to a chosen WireGuard tunnel. The app automatically configures the tunnel's include/exclude lists.
- **Conflict detection**: the manager warns if a tunnel is in an incompatible routing mode (e.g., trying to include apps when the tunnel is in exclude-only mode).
- **Dual include/exclude guard**: a single tunnel cannot simultaneously have include and exclude policies.

The existing tunnel editor's Routing tab still works as before. The Jail integration adds a higher-level interface on top.

### 2. Work Profile App Management

Provision, detect, and install apps in an Android [work profile](https://support.google.com/work/answer/6194897) — without requiring a full MDM solution:

- **Profile provisioning** (`ManagedProfileProvisioningManager`): checks if managed profile provisioning is supported, allowed, and launchable; provides a pre-configured `DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE` intent.
- **Work profile detection** (`WorkProfileManager`, `ManagedProfileDetector`): observes the profile state via `UserManager` — confirmed present, absent, or uncertain.
- **Automated app install** (`WorkProfileAppInstallService`): uses `DevicePolicyManager.installExistingPackage()` (API 28+) to silently install an already-downloaded app into the work profile.
- **Manual fallback**: when automatic install is unavailable, opens Google Play (`market://`) for the user to install manually.
- **Setup wizard** (`WorkProfileSetupWizard`): step-by-step guidance covering what a work profile is, detection, installation, icon behavior, limitations, and honest messaging.
- **App catalog** (`WorkProfileAppCatalogService`): scans installed packages and reports their installability status (already installed in work profile, can auto-install, needs manual install, unavailable).
- **Device admin receiver** (`JailDeviceAdminReceiver`): minimal DPC component required for managed profile provisioning.

### 3. App Audit & Risk Analysis

Inspect installed apps for capabilities and permissions that could impact privacy:

- **Permission inspector**: checks runtime-granted permissions (location, microphone, camera, contacts, SMS, call log, phone state, body sensors).
- **Accessibility & notification listener detection**: identifies when an app has enabled an accessibility service or notification listener (both high-signal capabilities).
- **Foreground service inspector**: detects persistent foreground services (location, microphone, camera types).
- **Background audit**: checks battery optimization exemption status.
- **Risk scoring** (`AppAuditManager`): computes a weighted score (0–100) based on all collected signals. Signals are sorted by severity:
  - Critical: accessibility service enabled
  - High: overlay declared, notification listener, camera/microphone/location granted
  - Medium: contacts, SMS, call log, phone state
  - Low: body sensors, external storage, usage stats, battery exemption
- **Capability matrix** (`CapabilityMatrix`): aggregates raw audit data into user-facing categories — what an app *can see*, *probably can see*, and *probably cannot see*.
- **Risk report** (`RiskReportBuilder`): generates plain-language reports with string resource IDs, split into sections: "Can see", "Cannot see", "Work profile", "Network metadata", "Residual risks".
- **Badge system** (`JailAppClassifier`): apps in the list display badges: `High risk`, `Selected`, `In work profile`, `Work profile missing`, `System app`.
- **Sterile launch** (`SterileLaunchManager`): a pre-launch checklist (tunnel status, app selection, risk level, work profile copy) and profile-aware launch via `CrossProfileApps`.

---

## UI Changes

The main screen gains a **Jail tab** with these sub-sections:

| Section | Purpose |
|---------|---------|
| **Overview** | Feature cards introducing each Jail capability |
| **Setup** | Work profile setup wizard |
| **Apps** | Installed apps list with search, selection, and risk badges |
| **Launch** | Pre-launch checklist + profile-aware app launch |
| **Report** | Plain-language risk report for a selected app |

Existing WireGuard screens (tunnel list, editor, settings, log viewer) are untouched.

---

## Building

```
$ git clone --recurse-submodules https://github.com/ViAl/wireguard-android
$ cd wireguard-android
$ ./gradlew assembleRelease
```

macOS users may need [flock(1)](https://github.com/discoteq/flock).

---

## Embedding

The tunnel library is [on Maven Central](https://search.maven.org/artifact/com.wireguard.android/tunnel), alongside [extensive class library documentation](https://javadoc.io/doc/com.wireguard.android/tunnel).

```groovy
implementation 'com.wireguard.android.tunnel:$wireguardTunnelVersion'
```

The library makes use of Java 8 features, so be sure to support those in your gradle configuration with [desugaring](https://developer.android.com/studio/write/java8-support#library-desugaring):

```groovy
compileOptions {
    sourceCompatibility JavaVersion.VERSION_17
    targetCompatibility JavaVersion.VERSION_17
    coreLibraryDesugaringEnabled = true
}
dependencies {
    coreLibraryDesugaring "com.android.tools:desugar_jdk_libs:2.0.3"
}
```

---

## Translating

Help translate the app into several languages on [our translation platform](https://crowdin.com/project/WireGuard).

---

## Architecture

Jail feature code lives under `ui/src/main/java/com/wireguard/android/jail/`:

```
jail/
├── JailComponent.kt              # Composition root (DI)
├── domain/
│   ├── AppAuditManager.kt        # Permission & capability audit orchestration
│   ├── JailAppClassifier.kt      # Badge computation
│   ├── JailAppRepository.kt      # Reactive app list with selection
│   ├── JailAuditRepository.kt    # Cached audit snapshots
│   ├── PerAppVpnManager.kt       # Applies jail routing to a tunnel
│   ├── RiskReportBuilder.kt      # Plain-language report generation
│   ├── SterileLaunchManager.kt   # Pre-launch checklist + profile-aware launch
│   ├── WorkProfileInstallGuide.kt # Play Store deep links
│   ├── WorkProfileManager.kt     # Profile state observer
│   └── WorkProfileSetupWizard.kt # Setup wizard steps
├── enterprise/
│   ├── InstallEnvironmentInspector.kt
│   ├── JailDeviceAdminReceiver.kt
│   ├── ManagedProfileOwnershipService.kt
│   ├── ManagedProfileProvisioningManager.kt
│   ├── WorkProfileAppCatalogService.kt
│   ├── WorkProfileAppInstallCapabilityChecker.kt
│   └── WorkProfileAppInstallService.kt
├── model/
│   ├── AuditConfidence.kt / AuditRiskLevel.kt / AuditRiskScore.kt
│   ├── AuditSignal.kt / AuditSnapshot.kt
│   ├── BackgroundAuditResult.kt / PermissionAuditResult.kt
│   ├── CapabilityMatrix.kt
│   ├── InstallResult.kt
│   ├── JailAppBadge.kt / JailAppInfo.kt
│   ├── JailDestination.kt
│   ├── JailRoutingPolicy.kt / JailTunnelBinding.kt / JailTunnelMode.kt
│   ├── LaunchProfile.kt
│   ├── ManagedProfileOwnershipState.kt
│   ├── RiskReport.kt
│   ├── SterileLaunchChecklist.kt / SterileLaunchPreset.kt / SterileLaunchResult.kt
│   ├── VisibilityEstimate.kt
│   └── WorkProfile*.kt
├── storage/
│   ├── AuditSnapshotCodec.kt
│   ├── JailSelectionStore.kt
│   ├── JailStore.kt
│   ├── LaunchPresetCodec.kt
│   └── RoutingPolicyCodec.kt
├── system/
│   ├── AccessibilityInspector.kt
│   ├── CrossProfileAppsWrapper.kt
│   ├── ForegroundServiceInspector.kt
│   ├── InstalledAppsSource.kt
│   ├── ManagedProfileDetector.kt
│   ├── NotificationAccessInspector.kt
│   ├── PermissionInspector.kt
│   └── PowerManagerWrapper.kt
└── ui/
    ├── HumanReadableRiskFormatter.kt
    ├── JailAppDetailFragment.kt
    ├── JailAppsFragment.kt
    ├── JailFragment.kt / JailFragmentHost.kt
    ├── JailHelpFragment.kt
    ├── JailLaunchFragment.kt
    ├── JailNavigationController.kt
    ├── JailOverviewFragment.kt
    ├── JailPlaceholderFragment.kt
    ├── JailReportFragment.kt
    └── JailSetupWizardFragment.kt
```

## License

This project is distributed under the Apache License, Version 2.0 — same as the upstream [WireGuard Android](https://git.zx2c4.com/wireguard-android/) project.
