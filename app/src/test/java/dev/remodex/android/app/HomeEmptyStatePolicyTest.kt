package dev.remodex.android.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeEmptyStatePolicyTest {
    @Test
    fun showsOnboardingEvenWhenDeviceHistoryExistsIfNoTrustedDeviceIsActive() {
        assertTrue(
            shouldShowHomeOnboarding(
                hasTrustedMac = false,
                hasDeviceHistory = true,
            ),
        )
    }

    @Test
    fun hidesOnboardingWhenTrustedDeviceExists() {
        assertFalse(
            shouldShowHomeOnboarding(
                hasTrustedMac = true,
                hasDeviceHistory = false,
            ),
        )
    }
}
