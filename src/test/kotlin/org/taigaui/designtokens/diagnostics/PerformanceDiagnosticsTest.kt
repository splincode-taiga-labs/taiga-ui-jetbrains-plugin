package org.taigaui.designtokens.diagnostics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceDiagnosticsTest {
    @After
    fun tearDown() {
        PerformanceDiagnostics.reset()
        PerformanceDiagnostics.setEnabledForTests(null)
    }

    @Test
    fun `does not record measurements when diagnostics are disabled`() {
        PerformanceDiagnostics.setEnabledForTests(false)

        PerformanceDiagnostics.measure(PerformanceMetric.PACKAGE_SCAN) {
            Unit
        }

        assertEquals(0L, PerformanceDiagnostics.snapshot().getValue(PerformanceMetric.PACKAGE_SCAN).count)
    }

    @Test
    fun `reads diagnostics toggle from system property when test override is absent`() {
        val property = "taiga.design.tokens.performanceDiagnostics"
        val previous = System.getProperty(property)

        PerformanceDiagnostics.setEnabledForTests(null)
        System.setProperty(property, "true")

        try {
            PerformanceDiagnostics.measure(PerformanceMetric.VALUE_RESOLUTION) {
                Unit
            }

            assertEquals(
                1L,
                PerformanceDiagnostics
                    .snapshot()
                    .getValue(PerformanceMetric.VALUE_RESOLUTION)
                    .count,
            )
        } finally {
            if (previous == null) {
                System.clearProperty(property)
            } else {
                System.setProperty(property, previous)
            }
        }
    }

    @Test
    fun `records count total and maximum duration when diagnostics are enabled`() {
        PerformanceDiagnostics.setEnabledForTests(true)

        repeat(2) {
            PerformanceDiagnostics.measure(PerformanceMetric.PACKAGE_SCAN) {
                Unit
            }
        }

        val measurement = PerformanceDiagnostics.snapshot().getValue(PerformanceMetric.PACKAGE_SCAN)

        assertEquals(2L, measurement.count)
        assertTrue(measurement.totalNanos >= measurement.maxNanos)
        assertTrue(measurement.maxNanos >= 0)
    }
}
