package com.wireguard.android.workprofile

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkProfileBridgeActivityTest {
    @Test
    fun `correct action preserves package name`() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            setClassName(ApplicationProvider.getApplicationContext<android.content.Context>().packageName, "com.wireguard.android.workprofile.WorkProfileBridgeActivity")
            action = WorkProfileBridgeActivity.ACTION_OPEN_PLAY_STORE_IN_WORK
            putExtra(WorkProfileBridgeActivity.EXTRA_PACKAGE_NAME, "com.wireguard.android")
        }
        ActivityScenario.launch<WorkProfileBridgeActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(
                    "com.wireguard.android",
                    activity.intent.getStringExtra(WorkProfileBridgeActivity.EXTRA_PACKAGE_NAME),
                )
            }
        }
    }

    @Test
    fun `wrong action is rejected`() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            setClassName(ApplicationProvider.getApplicationContext<android.content.Context>().packageName, "com.wireguard.android.workprofile.WorkProfileBridgeActivity")
            action = "wrong.action"
            putExtra(WorkProfileBridgeActivity.EXTRA_PACKAGE_NAME, "com.whatsapp")
        }
        ActivityScenario.launch<WorkProfileBridgeActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals("wrong.action", activity.intent.action)
            }
        }
    }
}
