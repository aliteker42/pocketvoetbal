package com.opencapture.openpocketcine

import android.content.Context
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opencapture.monitorui.settings.MonitorSettingsTab
import com.opencapture.monitorui.settings.REMOTE_SHUTTER_TAG
import com.opencapture.opencine.cameracontrols.OperatorSetupScreen
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SIMULATED (no camera): the remote shutter row is in Operator Setup > Controls, is opt-in,
 * and the operator's choice survives a new app model.
 */
@RunWith(AndroidJUnit4::class)
class RemoteShutterSettingTest {
    @Test fun theRemoteShutterRowAppearsInControlsAndItsChoicePersists() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("openpocketcine.operator", Context.MODE_PRIVATE)
        val key = "OpenPocketCine.RemoteShutter"
        val previous = prefs.all[key] as Boolean?
        assertTrue(prefs.edit().remove(key).commit())
        try {
            ActivityScenario.launch(BackdropRenderActivity::class.java).use { scenario ->
                var model: AppModel? = null
                fun mount() {
                    scenario.onActivity { activity ->
                        val fresh = AppModel(activity)
                        fresh.operatorSettingsTab = MonitorSettingsTab.CONTROLS
                        model = fresh
                        activity.setContent {
                            Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                                OperatorSetupScreen(fresh, fresh.operatorSetupState(), operatorSetupApp(fresh), onClose = {})
                            }
                        }
                    }
                    settle()
                }
                try {
                    mount()
                    val row = remoteShutterRow()
                    // Native Compose exposes the title as a child of the tagged switch.
                    val title = find(row) { it.contentDescription != null }
                    assertEquals("Bluetooth Remote Shutter", title?.contentDescription?.toString())
                    assertTrue(row.isClickable, "The tagged switch owns the toggle action")
                    assertEquals("Off", row.stateDescription?.toString(), "Bluetooth Remote Shutter is opt-in")
                    assertTrue(row.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    settle()
                    assertEquals("On", remoteShutterRow().stateDescription?.toString())
                    assertEquals(true, OperatorPrefs.remoteShutterEnabled(context))
                    scenario.onActivity { model?.close(); model = null }
                    scenario.recreate()
                    mount()
                    assertEquals("On", remoteShutterRow().stateDescription?.toString(), "The choice persists")
                } finally { scenario.onActivity { model?.close() } }
            }
        } finally {
            val editor = prefs.edit().remove(key)
            previous?.let { editor.putBoolean(key, it) }
            assertTrue(editor.commit(), "Restore the saved choice")
        }
    }

    private fun remoteShutterRow(): AccessibilityNodeInfo {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        repeat(20) {
            instrumentation.uiAutomation.rootInActiveWindow?.let { root ->
                find(root) { it.viewIdResourceName == REMOTE_SHUTTER_TAG }?.let { return it }
            }
            settle()
        }
        error("The remote shutter row is not in Controls")
    }

    private fun find(root: AccessibilityNodeInfo, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (matches(root)) return root
        for (index in 0 until root.childCount) root.getChild(index)?.let { child ->
            find(child, matches)?.let { return it }
        }
        return null
    }

    private fun settle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(180)
    }
}
