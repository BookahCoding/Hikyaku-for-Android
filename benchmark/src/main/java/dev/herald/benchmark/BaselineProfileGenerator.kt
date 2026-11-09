package dev.herald.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run against the unminified profile variant; generated source-name rules are rewritten by R8. */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val baseline = BaselineProfileRule()

    @Test fun generate() = baseline.collect(
        packageName = TARGET, maxIterations = 10, stableIterations = 2,
        // Library profiles merge independently. Exclude synthetic callers from shipped rules.
        filterPredicate = { rule ->
            "Ldev/hermeskotlin/" in rule && "/preview/" !in rule && "PerformanceFixture" !in rule
        },
    ) {
        pressHome()
        openFixture("long")
        scrollChat()
        openFixture("stream")
        streamChat()
        openFixture("switch")
        switchSessions()
    }
}
