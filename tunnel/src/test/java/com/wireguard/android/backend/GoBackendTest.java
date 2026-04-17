/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.backend;

import android.content.pm.PackageManager;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class GoBackendTest {
    @Test
    public void applyApplicationRulesSkipsMissingPackages() {
        final List<String> appliedPackages = new ArrayList<>();
        final int applied = GoBackend.applyApplicationRules(
                "test-tunnel",
                "excluded",
                List.of("com.example.valid", "com.example.missing", "com.example.valid2"),
                packageName -> {
                    if ("com.example.missing".equals(packageName))
                        throw new PackageManager.NameNotFoundException(packageName);
                    appliedPackages.add(packageName);
                }
        );
        assertEquals(2, applied);
        assertEquals(List.of("com.example.valid", "com.example.valid2"), appliedPackages);
    }

    @Test
    public void applyApplicationRulesSkipsMissingPackagesForIncludedMode() {
        final List<String> appliedPackages = new ArrayList<>();
        final int applied = GoBackend.applyApplicationRules(
                "test-tunnel",
                "included",
                List.of("com.example.valid", "com.example.missing"),
                packageName -> {
                    if ("com.example.missing".equals(packageName))
                        throw new PackageManager.NameNotFoundException(packageName);
                    appliedPackages.add(packageName);
                }
        );
        assertEquals(1, applied);
        assertEquals(List.of("com.example.valid"), appliedPackages);
    }

    @Test
    public void applyApplicationRulesReportsNoAppliedWhenAllMissing() {
        final int applied = GoBackend.applyApplicationRules(
                "test-tunnel",
                "included",
                List.of("com.example.missing1", "com.example.missing2"),
                packageName -> {
                    throw new PackageManager.NameNotFoundException(packageName);
                }
        );
        assertEquals(0, applied);
    }

    @Test
    public void applyApplicationRulesHandlesEmptyRules() {
        final int applied = GoBackend.applyApplicationRules(
                "test-tunnel",
                "included",
                List.of(),
                packageName -> {
                    throw new AssertionError("adder should not be called for empty inputs");
                }
        );
        assertEquals(0, applied);
    }
}
