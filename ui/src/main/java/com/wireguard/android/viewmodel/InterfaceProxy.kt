/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.viewmodel

import android.os.Parcel
import android.os.Parcelable
import androidx.databinding.BaseObservable
import androidx.databinding.Bindable
import androidx.databinding.ObservableArrayList
import androidx.databinding.ObservableList
import com.wireguard.android.BR
import com.wireguard.config.Attribute
import com.wireguard.config.BadConfigException
import com.wireguard.config.Interface
import com.wireguard.config.BadConfigException.Location
import com.wireguard.config.BadConfigException.Reason
import com.wireguard.config.BadConfigException.Section
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyFormatException
import com.wireguard.crypto.KeyPair

enum class SplitTunnelingMode {
    ALL_APPLICATIONS,
    EXCLUDE_SELECTED_APPLICATIONS,
    INCLUDE_ONLY_SELECTED_APPLICATIONS
}

class InterfaceProxy : BaseObservable, Parcelable {
    @get:Bindable
    val excludedApplications: ObservableList<String> = ObservableArrayList()

    @get:Bindable
    val includedApplications: ObservableList<String> = ObservableArrayList()

    @get:Bindable
    var splitTunnelingMode: SplitTunnelingMode = SplitTunnelingMode.ALL_APPLICATIONS
        set(value) {
            if (field == value)
                return
            field = value
            normalizeForSplitTunnelingMode()
            notifyPropertyChanged(BR.splitTunnelingMode)
        }

    @get:Bindable
    var addresses: String = ""
        set(value) {
            field = value
            notifyPropertyChanged(BR.addresses)
        }

    @get:Bindable
    var dnsServers: String = ""
        set(value) {
            field = value
            notifyPropertyChanged(BR.dnsServers)
        }

    @get:Bindable
    var listenPort: String = ""
        set(value) {
            field = value
            notifyPropertyChanged(BR.listenPort)
        }

    @get:Bindable
    var mtu: String = ""
        set(value) {
            field = value
            notifyPropertyChanged(BR.mtu)
        }

    @get:Bindable
    var privateKey: String = ""
        set(value) {
            field = value
            notifyPropertyChanged(BR.privateKey)
            notifyPropertyChanged(BR.publicKey)
        }

    @get:Bindable
    val publicKey: String
        get() = try {
            KeyPair(Key.fromBase64(privateKey)).publicKey.toBase64()
        } catch (ignored: KeyFormatException) {
            ""
        }

    private constructor(parcel: Parcel) {
        addresses = parcel.readString() ?: ""
        dnsServers = parcel.readString() ?: ""
        parcel.readStringList(excludedApplications)
        parcel.readStringList(includedApplications)
        val remaining = mutableListOf<String?>()
        while (parcel.dataAvail() > 0) {
            remaining.add(parcel.readString())
        }
        val parcelTail = decodeParcelTail(remaining)
        listenPort = parcelTail.listenPort ?: ""
        mtu = parcelTail.mtu ?: ""
        privateKey = parcelTail.privateKey ?: ""
        splitTunnelingMode = parcelTail.mode ?: inferSplitTunnelingMode()
    }

    constructor(other: Interface) {
        addresses = Attribute.join(other.addresses)
        val dnsServerStrings = other.dnsServers.map { it.hostAddress }.plus(other.dnsSearchDomains)
        dnsServers = Attribute.join(dnsServerStrings)
        excludedApplications.addAll(other.excludedApplications)
        includedApplications.addAll(other.includedApplications)
        splitTunnelingMode = inferSplitTunnelingMode()
        listenPort = other.listenPort.map { it.toString() }.orElse("")
        mtu = other.mtu.map { it.toString() }.orElse("")
        val keyPair = other.keyPair
        privateKey = keyPair.privateKey.toBase64()
    }

    constructor()

    override fun describeContents() = 0

    fun generateKeyPair() {
        val keyPair = KeyPair()
        privateKey = keyPair.privateKey.toBase64()
        notifyPropertyChanged(BR.privateKey)
        notifyPropertyChanged(BR.publicKey)
    }

    @Throws(BadConfigException::class)
    fun resolve(): Interface {
        val builder = Interface.Builder()
        val resolvedApplications = resolveApplicationsForMode()
        if (addresses.isNotEmpty()) builder.parseAddresses(addresses)
        if (dnsServers.isNotEmpty()) builder.parseDnsServers(dnsServers)
        if (resolvedApplications.excludedApplications.isNotEmpty())
            builder.excludeApplications(resolvedApplications.excludedApplications)
        if (resolvedApplications.includedApplications.isNotEmpty())
            builder.includeApplications(resolvedApplications.includedApplications)
        if (listenPort.isNotEmpty()) builder.parseListenPort(listenPort)
        if (mtu.isNotEmpty()) builder.parseMtu(mtu)
        if (privateKey.isNotEmpty()) builder.parsePrivateKey(privateKey)
        return builder.build()
    }

    fun inferSplitTunnelingMode(): SplitTunnelingMode {
        return when {
            excludedApplications.isNotEmpty() -> SplitTunnelingMode.EXCLUDE_SELECTED_APPLICATIONS
            includedApplications.isNotEmpty() -> SplitTunnelingMode.INCLUDE_ONLY_SELECTED_APPLICATIONS
            else -> SplitTunnelingMode.ALL_APPLICATIONS
        }
    }

    fun normalizeForSplitTunnelingMode() {
        when (splitTunnelingMode) {
            SplitTunnelingMode.ALL_APPLICATIONS -> {
                excludedApplications.clear()
                includedApplications.clear()
            }

            SplitTunnelingMode.EXCLUDE_SELECTED_APPLICATIONS -> includedApplications.clear()
            SplitTunnelingMode.INCLUDE_ONLY_SELECTED_APPLICATIONS -> excludedApplications.clear()
        }
    }

    @Throws(BadConfigException::class)
    private fun resolveApplicationsForMode(): ResolvedApplications {
        val normalizedExcludedApplications = excludedApplications.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val normalizedIncludedApplications = includedApplications.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val resolvedApplications = when (splitTunnelingMode) {
            // `resolve()` is the final emission gate for editor-originated split-tunneling state.
            // Regardless of stale/legacy list contents, only the currently selected mode may emit.
            SplitTunnelingMode.ALL_APPLICATIONS -> ResolvedApplications(emptyList(), emptyList())
            SplitTunnelingMode.EXCLUDE_SELECTED_APPLICATIONS -> ResolvedApplications(normalizedExcludedApplications, emptyList())
            SplitTunnelingMode.INCLUDE_ONLY_SELECTED_APPLICATIONS -> ResolvedApplications(emptyList(), normalizedIncludedApplications)
        }
        if (resolvedApplications.excludedApplications.isNotEmpty() && resolvedApplications.includedApplications.isNotEmpty()) {
            throw BadConfigException(Section.INTERFACE, Location.INCLUDED_APPLICATIONS, Reason.INVALID_KEY, "Dual include/exclude application rules are not allowed")
        }
        return resolvedApplications
    }

    private data class ResolvedApplications(
        val excludedApplications: List<String>,
        val includedApplications: List<String>
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(addresses)
        dest.writeString(dnsServers)
        dest.writeStringList(excludedApplications)
        dest.writeStringList(includedApplications)
        dest.writeString(listenPort)
        dest.writeString(mtu)
        dest.writeString(privateKey)
        // Appended at end for parcel backward compatibility. Older readers ignore trailing data.
        dest.writeString(splitTunnelingMode.name)
    }

    private class InterfaceProxyCreator : Parcelable.Creator<InterfaceProxy> {
        override fun createFromParcel(parcel: Parcel): InterfaceProxy {
            return InterfaceProxy(parcel)
        }

        override fun newArray(size: Int): Array<InterfaceProxy?> {
            return arrayOfNulls(size)
        }
    }

    companion object {
        internal data class DecodedParcelTail(
            val listenPort: String?,
            val mtu: String?,
            val privateKey: String?,
            val mode: SplitTunnelingMode?
        )

        internal fun decodeParcelTail(remainingFields: List<String?>): DecodedParcelTail {
            if (remainingFields.size <= 3) {
                return DecodedParcelTail(
                    listenPort = remainingFields.getOrNull(0),
                    mtu = remainingFields.getOrNull(1),
                    privateKey = remainingFields.getOrNull(2),
                    mode = null
                )
            }
            val firstFieldMode = remainingFields.firstOrNull()?.let { runCatching { SplitTunnelingMode.valueOf(it) }.getOrNull() }
            val lastFieldMode = remainingFields.lastOrNull()?.let { runCatching { SplitTunnelingMode.valueOf(it) }.getOrNull() }
            return when {
                // Intermediate split-tunneling branch format: mode field inserted before listenPort.
                firstFieldMode != null -> DecodedParcelTail(
                    listenPort = remainingFields.getOrNull(1),
                    mtu = remainingFields.getOrNull(2),
                    privateKey = remainingFields.getOrNull(3),
                    mode = firstFieldMode
                )
                // Final format: mode appended after old parcel fields for backward compatibility.
                lastFieldMode != null -> DecodedParcelTail(
                    listenPort = remainingFields.getOrNull(0),
                    mtu = remainingFields.getOrNull(1),
                    privateKey = remainingFields.getOrNull(2),
                    mode = lastFieldMode
                )
                else -> DecodedParcelTail(
                    listenPort = remainingFields.getOrNull(0),
                    mtu = remainingFields.getOrNull(1),
                    privateKey = remainingFields.getOrNull(2),
                    mode = null
                )
            }
        }

        @JvmField
        val CREATOR: Parcelable.Creator<InterfaceProxy> = InterfaceProxyCreator()
    }
}
