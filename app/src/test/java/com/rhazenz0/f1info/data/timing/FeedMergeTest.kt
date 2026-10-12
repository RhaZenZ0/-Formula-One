package com.rhazenz0.f1info.data.timing

import com.rhazenz0.f1info.util.AppJson
import com.rhazenz0.f1info.util.at
import com.rhazenz0.f1info.util.elements
import com.rhazenz0.f1info.util.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeedMergeTest {
    private fun j(s: String) = AppJson.parseToJsonElement(s)

    @Test
    fun mergesNestedObjects() {
        val base = j("""{"Lines":{"1":{"Position":"4","LastLapTime":{"Value":"1:38.977"}}}}""")
        val patch = j("""{"Lines":{"1":{"LastLapTime":{"Value":"1:38.396","PersonalFastest":true}}}}""")
        val merged = FeedMerge.merge(base, patch)
        assertEquals("4", merged.at("Lines", "1", "Position").text())
        assertEquals("1:38.396", merged.at("Lines", "1", "LastLapTime", "Value").text())
        assertEquals("true", merged.at("Lines", "1", "LastLapTime", "PersonalFastest").text())
    }

    @Test
    fun indexKeyedObjectPatchesAndAppendsToArrays() {
        val base = j("""{"Stints":[{"Compound":"SOFT","TotalLaps":10}]}""")
        val patch = j("""{"Stints":{"0":{"TotalLaps":32},"1":{"Compound":"MEDIUM","TotalLaps":0}}}""")
        val stints = FeedMerge.merge(base, patch).at("Stints").elements()
        assertEquals(2, stints.size)
        assertEquals("SOFT", stints[0].at("Compound").text())
        assertEquals("32", stints[0].at("TotalLaps").text())
        assertEquals("MEDIUM", stints[1].at("Compound").text())
    }

    @Test
    fun deletedRemovesKeys() {
        val base = j("""{"PitTimes":{"44":{"Duration":"24.0"},"3":{"Duration":"23.6"}}}""")
        val merged = FeedMerge.merge(base, j("""{"PitTimes":{"_deleted":["44"]}}"""))
        assertNull(merged.at("PitTimes", "44"))
        assertEquals("23.6", merged.at("PitTimes", "3", "Duration").text())
    }

    @Test
    fun primitivesAndArraysReplace() {
        val merged = FeedMerge.merge(j("""{"a":[1,2,3],"b":"x"}"""), j("""{"a":[9],"b":"y"}"""))
        assertEquals(1, merged.at("a").elements().size)
        assertEquals("y", merged.at("b").text())
    }
}
