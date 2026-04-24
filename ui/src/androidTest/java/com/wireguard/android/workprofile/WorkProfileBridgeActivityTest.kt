package com.wireguard.android.workprofile

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkProfileBridgeActivityTest {

    @Test
    fun testInvalidAction_returnsError() {
        val intent = Intent(Intent.ACTION_VIEW) // Incorrect action
        
        val scenario = ActivityScenario.launchActivityForResult<WorkProfileBridgeActivity>(intent)
        
        assertEquals(android.app.Activity.RESULT_OK, scenario.result.resultCode)
        val result = scenario.result.resultData.getSerializableExtra(WorkProfileInstallCoordinator.RESULT_EXTRA_CLONE_RESULT) as PackageCloneResult
        assert(result is PackageCloneResult.ErrorUnknown)
    }

    @Test
    fun testValidActionWithPackage_returnsCorrectResult() {
        val intent = Intent(WorkProfileBridgeActivity.ACTION_BRIDGE_EXECUTE).apply {
            putExtra(WorkProfileInstallCoordinator.EXTRA_COMMAND, WorkProfileInstallCoordinator.COMMAND_OPEN_PLAY_STORE)
            putExtra(WorkProfileInstallCoordinator.EXTRA_PACKAGE_NAME, "com.test.app")
        }
        
        val scenario = ActivityScenario.launchActivityForResult<WorkProfileBridgeActivity>(intent)
        
        assertEquals(android.app.Activity.RESULT_OK, scenario.result.resultCode)
        val result = scenario.result.resultData.getSerializableExtra(WorkProfileInstallCoordinator.RESULT_EXTRA_CLONE_RESULT) as PackageCloneResult
        assert(result is PackageCloneResult.RedirectedToPlayStore || result is PackageCloneResult.ErrorPlayStoreUnavailable)
    }
}
