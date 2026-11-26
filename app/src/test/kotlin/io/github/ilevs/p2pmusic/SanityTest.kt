package io.github.ilevs.p2pmusic

import org.junit.Assert.assertEquals
import org.junit.Test

class SanityTest {
    @Test
    fun arithmeticWorks() {
        assertEquals(EXPECTED_SUM, 2 + 2)
    }

    private companion object {
        const val EXPECTED_SUM = 4
    }
}
