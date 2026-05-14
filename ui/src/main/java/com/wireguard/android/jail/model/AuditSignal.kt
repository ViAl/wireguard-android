/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.jail.model

import androidx.annotation.StringRes
import com.wireguard.android.R

/**
 * Registry of signals that feed into the Jail risk score.
 *
 * Each entry is intentionally self-describing: the scorer in
 * `com.wireguard.android.jail.domain.AppAuditManager` only needs to iterate over
 * the triggered signals, sum [baseWeight], and honour [criticalFloor]. This keeps
 * the scoring table reviewable in one place.
 *
 * Weight tuning notes:
 *  * Accessibility enabled is the single strongest signal on Android: a rogue
 *    accessibility service can observe UI across apps, read text, and synthesise
 *    input. It is both heavy and a [criticalFloor].
 *  * Overlay / notification listener access are dangerous but can be revoked,
 *    so they weigh a little less than accessibility.
 *  * Runtime-dangerous permissions are weighted by how much they leak about the
 *    *user outside* of the app (microphone > location > contacts > sensors).
 *  * LIKELY signals (manifest-only declarations) are slightly discounted versus
 *    KNOWN grants, but not zeroed — a declared permission the user never saw is
 *    still latent exposure.
 */
enum class AuditSignal(
    val id: String,
    val baseWeight: Int,
    val confidence: AuditConfidence,
    val criticalFloor: Boolean,
    @StringRes val shortRes: Int,
    @StringRes val detailRes: Int,
) {
    ACCESSIBILITY_ENABLED(
        id = "accessibility_enabled",
        baseWeight = 60,
        confidence = AuditConfidence.KNOWN,
        criticalFloor = true,
        shortRes = R.string.jail_signal_accessibility_enabled_short,
        detailRes = R.string.jail_signal_accessibility_enabled_detail,
    ),
    ACCESSIBILITY_DECLARED(
        id = "accessibility_declared",
        baseWeight = 40,
        confidence = AuditConfidence.LIKELY,
        criticalFloor = false,
        shortRes = R.string.jail_signal_accessibility_declared_short,
        detailRes = R.string.jail_signal_accessibility_declared_detail,
    ),
    OVERLAY_DECLARED(
        id = "overlay_declared",
        baseWeight = 30,
        confidence = AuditConfidence.LIKELY,
        criticalFloor = false,
        shortRes = R.string.jail_signal_overlay_short,
        detailRes = R.string.jail_signal_overlay_detail,
    ),
    NOTIFICATION_LISTENER_ENABLED(
        id = "notif_listener_enabled",
        baseWeight = 35,
        confidence = AuditConfidence.KNOWN,
        criticalFloor = false,
        shortRes = R.string.jail_signal_notif_listener_enabled_short,
        detailRes = R.string.jail_signal_notif_listener_enabled_detail,
    ),
    NOTIFICATION_LISTENER_DECLARED(
        id = "notif_listener_declared",
        baseWeight = 15,
        confidence = AuditConfidence.LIKELY,
        criticalFloor = false,
        shortRes = R.string.jail_signal_notif_listener_declared_short,
        detailRes = R.string.jail_signal_notif_listener_declared_detail,
    ),
    USAGE_STATS_DECLARED(
        id = "usage_stats_declared",
        baseWeight = 15,
        confidence = AuditConfidence.LIKELY,
        criticalFloor = false,
        shortRes = R.string.jail_signal_usage_stats_short,
        detailRes = R.string.jail_signal_usage_stats_detail,
    ),
    MICROPHONE_GRANTED(
        id = "microphone",
        baseWeight = 25,
        confidence = AuditConfidence.KNOWN,
        criticalFloor = false,
        shortRes = R.string.jail_signal_microphone_short,
        detailRes = R.string.jail_signal_microphone_detail,
    ),
    CAMERA_GRANTED(
        id = "camera",
        baseWeight = 18,
        confidence = AuditConfidence.KNOWN,
        criticalFloor = false,
        shortRes = R.string.jail_signal_camera_short,
        detailRes = R.string.jail_signal_camera_detail,
    ),
    LOCATION_FOREGROUND_GRANTED(
        id = "location_fg",
        baseWeight = 22,
        confidence = AuditConfidence.KNOWN,
        criticalFloor = false,
        shortRes = R.string.jail_signal_location_short,
        detailRes = R.string.jail_signal_location_detail,
    ),
    LOCATION_BACKGROUND_GRANTED(
        id = "location_bg",
        baseWeight = 12,
        confidence = AuditConfidence.KNOWN,
        criticalFloor = false,
        shortRes = R.string.jail_signal_location_background_short,
        detailRes = R.string.jail_signal_location_background_detail,
    ),
    CONTACTS_GRANTED(
        id = "contacts",
        baseWeight = 12,
        confidence = AuditConfidence.KNOWN,
        criticalFloor = false,
        shortRes = R.string.jail_signal_contacts_short,
        detailRes = R.string.jail_signal_contacts_detail,
    ),
    SMS_GRANTED(
        id = "sms",
        baseWeight = 22,
        confidence = AuditConfidence.KNOWN,
        criticalFloor = false,
        shortRes = R.string.jail_signal_sms_short,
        detailRes = R.string.jail_signal_sms_detail,
    ),
    CALL_LOG_GRANTED(
        id = "call_log",
        baseWeight = 18,
        confidence = AuditConfidence.KNOWN,
        criticalFloor = false,
        shortRes = R.string.jail_signal_call_log_short,
        detailRes = R.string.jail_signal_call_log_detail,
    ),
    PHONE_STATE_GRANTED(
        id = "phone_state",
        baseWeight = 15,
        confidence = AuditConfidence.KNOWN,
        criticalFloor = false,
        shortRes = R.string.jail_signal_phone_state_short,
        detailRes = R.string.jail_signal_phone_state_detail,
    ),
    SENSORS_GRANTED(
        id = "sensors",
        baseWeight = 10,
        confidence = AuditConfidence.KNOWN,
        criticalFloor = false,
        shortRes = R.string.jail_signal_sensors_short,
        detailRes = R.string.jail_signal_sensors_detail,
    ),
    MANAGE_EXTERNAL_STORAGE_DECLARED(
        id = "storage_manage",
        baseWeight = 15,
        confidence = AuditConfidence.LIKELY,
        criticalFloor = false,
        shortRes = R.string.jail_signal_storage_manage_short,
        detailRes = R.string.jail_signal_storage_manage_detail,
    ),
    BATTERY_UNRESTRICTED(
        id = "battery_unrestricted",
        baseWeight = 10,
        confidence = AuditConfidence.LIKELY,
        criticalFloor = false,
        shortRes = R.string.jail_signal_battery_unrestricted_short,
        detailRes = R.string.jail_signal_battery_unrestricted_detail,
    ),
    FOREGROUND_SERVICE_LOCATION(
        id = "fg_service_location",
        baseWeight = 12,
        confidence = AuditConfidence.LIKELY,
        criticalFloor = false,
        shortRes = R.string.jail_signal_fg_service_location_short,
        detailRes = R.string.jail_signal_fg_service_location_detail,
    ),
    FOREGROUND_SERVICE_MICROPHONE(
        id = "fg_service_microphone",
        baseWeight = 18,
        confidence = AuditConfidence.LIKELY,
        criticalFloor = false,
        shortRes = R.string.jail_signal_fg_service_microphone_short,
        detailRes = R.string.jail_signal_fg_service_microphone_detail,
    ),
    FOREGROUND_SERVICE_CAMERA(
        id = "fg_service_camera",
        baseWeight = 12,
        confidence = AuditConfidence.LIKELY,
        criticalFloor = false,
        shortRes = R.string.jail_signal_fg_service_camera_short,
        detailRes = R.string.jail_signal_fg_service_camera_detail,
    );

    companion object {
        fun fromId(id: String?): AuditSignal? = entries.firstOrNull { it.id == id }
    }
}
