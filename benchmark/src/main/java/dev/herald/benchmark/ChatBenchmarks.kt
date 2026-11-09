package dev.herald.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Compare compilation modes on identical final source; this is not a source-code before/after. */
@RunWith(Parameterized::class)
class ChatBenchmarks(private val profile: Boolean) {
    @get:Rule val benchmark = MacrobenchmarkRule()
    private val compilation get() = if (profile) CompilationMode.Partial(
        baselineProfileMode = BaselineProfileMode.Require, warmupIterations = 0,
    ) else CompilationMode.None()
    private val iterations get() = InstrumentationRegistry.getArguments().getString("iterations")?.toInt() ?: 3

    @Test fun coldStartup() = benchmark.measureRepeated(
        packageName = TARGET, metrics = listOf(StartupTimingMetric()),
        compilationMode = compilation, startupMode = StartupMode.COLD, iterations = iterations,
        setupBlock = { pressHome() },
    ) { openFixture("long") }

    @Test fun longChatScroll() = benchmark.measureRepeated(
        packageName = TARGET, metrics = listOf(FrameTimingMetric()),
        compilationMode = compilation, iterations = iterations,
        setupBlock = { openFixture("long") },
    ) { scrollChat() }

    @Test fun streamingScroll() = benchmark.measureRepeated(
        packageName = TARGET, metrics = listOf(FrameTimingMetric()),
        compilationMode = compilation, iterations = iterations,
        setupBlock = { openFixture("stream") },
    ) { streamChat() }

    @Test fun sessionSwitching() = benchmark.measureRepeated(
        packageName = TARGET, metrics = listOf(FrameTimingMetric()),
        compilationMode = compilation, iterations = iterations,
        setupBlock = { openFixture("switch") },
    ) { switchSessions() }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "baselineProfile={0}")
        fun modes() = listOf(arrayOf(false), arrayOf(true))
    }
}
