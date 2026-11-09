package dev.herald.benchmark

import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Assertions read actual Markdown text nodes, not the fixture's state/status as a proxy. */
@RunWith(AndroidJUnit4::class)
class RenderingCorrectness {
    @Test fun rapidCompletionAndReplacementRenderExactCurrentText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        instrumentation.context.startActivity(Intent().apply {
            component = ComponentName(TARGET, "dev.hermeskotlin.android.PerformanceFixtureActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra("scenario", "correctness")
        })
        fun click(label: String) {
            val button = device.wait(Until.findObject(By.text(label)), 10_000)
            assertNotNull("Missing $label", button)
            button.click()
        }
        fun exact(text: String) {
            assertNotNull("Exact rendered source was not published", device.wait(Until.findObject(By.text(text)), 10_000))
            assertFalse("Obsolete source remained visible", device.hasObject(By.textContains("OBSOLETE_SOURCE")))
        }
        click("Start stream")
        exact("FINAL_REPLY: " + "The complete synthetic response is exact and current. ".repeat(45) + "FINAL_END.")
        click("Replace source")
        exact("REPLACEMENT_REPLY: " + "The replacement contains only the new synthetic source. ".repeat(45) + "REPLACEMENT_END.")
        assertFalse("Previous completed response remained visible", device.hasObject(By.textContains("FINAL_REPLY:")))
        device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
            device.displayWidth / 2, device.displayHeight / 3, 30)
        exact("REPLACEMENT_REPLY: " + "The replacement contains only the new synthetic source. ".repeat(45) + "REPLACEMENT_END.")
    }
}
