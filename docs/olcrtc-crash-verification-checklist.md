# Manual Verification Checklist — Go Lifecycle Crash Fix (PR #58)

## Prerequisites
- Build and install debug APK on physical device (Redmi Note 13 / garnet)
- Have at least one valid OlcRTC URI
- Run: `adb logcat -s "OlcRtcManager:V" "OlcRtcTransport:V" "OlcRtcVpnService:V"`

## Test 1: Normal Connect (Baseline)
1. Ensure no WireGuard tunnel is active
2. Open app → OlcRTC tab
3. Tap Connect on a valid tunnel
4. ✅ Verify: WireGuard tunnels (if any) stop
5. ✅ Verify: no `APP_SCOUT_WARNING` for `Mobile.waitReady` on main thread
6. ✅ Verify: `"Go waitReady begin"` log followed by `"Go waitReady completed"` (both on IO thread)
7. ✅ Verify: `"VpnService prepared, currentInstance=true, session=N"` log
8. ✅ Verify: `"VpnService created, session=N"` log (session IDs match)
9. ✅ Verify: state becomes CONNECTED
10. ✅ Verify: app traffic routes through VPN

## Test 2: Rapid Connect Taps (Duplicate Prevention)
1. Tap Connect once
2. Tap Connect repeatedly 5-10 times during startup (while "Connecting..." is shown)
3. ✅ Verify: Button shows "Connecting..." and is disabled after first tap
4. ✅ Verify: Log shows `"connect ignored: already CONNECTING"` for subsequent taps
5. ✅ Verify: No `"olcRTC already running"` error
6. ✅ Verify: No SIGSEGV crash
7. ✅ Verify: State eventually becomes CONNECTED

## Test 3: Connect → Disconnect Cycle (Cancellation Safety)
1. Tap Connect
2. Immediately tap Disconnect during startup (within 1-2 seconds)
3. ✅ Verify: Go client is stopped (log: `"Go client stopped"`)
4. ✅ Verify: VpnService is stopped (log: `"VpnService stopping"`)
5. ✅ Verify: State becomes DISCONNECTED
6. Tap Connect again
7. ✅ Verify: Fresh connect succeeds (no stale "olcRTC already running")

## Test 4: Connect → Home/Back (Cancellation via Lifecycle)
1. Tap Connect
2. Press Home or Back during startup
3. Wait 5 seconds
4. Reopen app
5. ✅ Verify: State is DISCONNECTED or ERROR (not stuck CONNECTING)
6. Tap Connect again
7. ✅ Verify: Connect succeeds cleanly

## Test 5: Startup Failure → Retry
1. Ensure no VPN permission is granted
2. Tap Connect
3. ✅ Verify: Permission dialog shown
4. Deny permission (or cancel)
5. Tap Connect again
6. ✅ Verify: Permission dialog shown again
7. Grant permission
8. ✅ Verify: Connect succeeds

## Test 6: Repeated Connect/Disconnect (Stress — 10 Cycles)
1. Connect → disconnect → connect → disconnect (10 cycles)
2. ✅ Verify: No ANR
3. ✅ Verify: No SIGSEGV
4. ✅ Verify: No stale "olcRTC already running" errors
5. ✅ Verify: No leaked notifications

## Test 7: WireGuard → OlcRTC Conflict
1. Start a WireGuard tunnel
2. Tap OlcRTC Connect
3. ✅ Verify: WireGuard tunnel stops automatically
4. ✅ Verify: OlcRTC connects successfully
5. Disconnect OlcRTC
6. Start WireGuard again
7. ✅ Verify: WireGuard works normally

## Test 8: Log Verification
Check logcat for these patterns:
- No `APP_SCOUT_SLOW` or `APP_SCOUT_WARNING` for main thread
- `"Go client start begin"` and `"Go client start end: success"` on IO thread
- No duplicate `"Go startWithTransport completed"` without matching stop
- Session IDs in VpnService match between create and transport signal
- `"connect ignored: already CONNECTING"` on duplicate taps
