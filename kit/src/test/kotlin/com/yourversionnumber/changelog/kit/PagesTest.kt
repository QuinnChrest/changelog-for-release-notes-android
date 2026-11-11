package com.yourversionnumber.changelog.kit

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PagesTest {
    @Test
    fun readsEveryPageInOrderUntilThereIsNoNext() = runTest {
        val pages = mapOf(null to ("a" to "2"), "2" to ("b" to "3"), "3" to ("c" to null))
        val got = mutableListOf<String>()
        val n = everyPage({ pages.getValue(it) }, { it.second }) { got += it.first }
        assertEquals(listOf("a", "b", "c"), got)
        assertEquals(3, n)
    }

    @Test
    fun aCursorThatComesRoundAgainEndsIt() = runTest {
        var reads = 0
        // Every page says "next is 2", forever.
        val n = everyPage({ reads++; "page" }, { "2" })
        assertEquals(2, n)
        assertEquals(2, reads)
    }
}
