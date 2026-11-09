package dev.herald.benchmark

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

internal const val TARGET = "dev.herald.android.benchmark"

internal fun MacrobenchmarkScope.openFixture(scenario: String) {
    // Streaming intentionally never becomes accessibility-idle until it completes.
    Configurator.getInstance().waitForIdleTimeout = 0
    startActivityAndWait(Intent().apply {
        component = ComponentName(TARGET, "dev.hermeskotlin.android.PerformanceFixtureActivity")
        putExtra("scenario", scenario)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    })
    awaitText("Synthetic session 0")
}

internal fun MacrobenchmarkScope.awaitText(text: String) {
    assertNotNull("Missing fixture text: $text", device.wait(Until.findObject(By.text(text)), 10_000))
}

internal fun MacrobenchmarkScope.tap(text: String) {
    val target = device.wait(Until.findObject(By.text(text)), 10_000)
    assertNotNull("Missing fixture control: $text", target)
    target.click()
}

internal fun MacrobenchmarkScope.scrollChat() {
    val x = device.displayWidth / 2
    val top = (device.displayHeight * 0.30).toInt()
    val bottom = (device.displayHeight * 0.72).toInt()
    repeat(3) { assertTrue("Chat swipe failed", device.swipe(x, top, x, bottom, 30)) }
    repeat(3) { assertTrue("Chat swipe failed", device.swipe(x, bottom, x, top, 30)) }
}

internal fun MacrobenchmarkScope.streamChat() {
    tap("Start stream")
    awaitText("Streaming")
    scrollChat()
    awaitText("Complete")
}

internal fun MacrobenchmarkScope.switchSessions() {
    repeat(6) { index ->
        tap("Next session")
        awaitText("Synthetic session ${(index + 1) % 3}")
    }
}
