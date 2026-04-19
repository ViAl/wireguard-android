/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.model

import android.graphics.drawable.Drawable
import androidx.databinding.BaseObservable
import androidx.databinding.Bindable
import com.wireguard.android.BR
import com.wireguard.android.databinding.Keyed

class ApplicationData(
    val icon: Drawable,
    val name: String,
    val packageName: String,
    val isSystemApp: Boolean,
    isSelected: Boolean
) : BaseObservable(), Keyed<String> {
    // Package name is stable and unique across installed apps; labels are not.
    override val key = packageName

    @get:Bindable
    var isSelected = isSelected
        set(value) {
            field = value
            notifyPropertyChanged(BR.selected)
        }
}
