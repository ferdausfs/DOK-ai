package neth.iecal.curbox.utils

import neth.iecal.curbox.data.models.AppTimeConfig
import neth.iecal.curbox.data.models.AppUsageConfig
import neth.iecal.curbox.data.models.KeywordBlocker
import neth.iecal.curbox.data.models.KeywordGroup
import neth.iecal.curbox.data.models.ScheduledUsageConfig
import neth.iecal.curbox.data.models.TimeInterval
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordGroupRestrictionComparatorTest {
    private val oldBlocker = blocker(startHour = 9, usageMinutes = 60)

    @Test
    fun lowerUsageAllowanceIsStricter() {
        assertTrue(
            RestrictionComparator.keywordBlocker(
                oldBlocker,
                blocker(startHour = 9, usageMinutes = 30)
            )
        )
    }

    @Test
    fun shorterActiveScheduleIsWeaker() {
        assertFalse(
            RestrictionComparator.keywordBlocker(
                oldBlocker,
                blocker(startHour = 10, usageMinutes = 60)
            )
        )
    }

    @Test
    fun higherUsageAllowanceIsWeaker() {
        assertFalse(
            RestrictionComparator.keywordBlocker(
                oldBlocker,
                blocker(startHour = 9, usageMinutes = 90)
            )
        )
    }

    private fun blocker(startHour: Int, usageMinutes: Long) = KeywordBlocker(
        isActive = true,
        keywordGroups = listOf(
            KeywordGroup(
                id = "group",
                selectedKeywords = listOf("example"),
                config = ScheduledUsageConfig(
                    schedule = AppTimeConfig(
                        everydayIntervals =
                            mutableListOf(TimeInterval(startHour, 0, 17, 0))
                    ),
                    usage = AppUsageConfig(uniformLimit = usageMinutes)
                ),
                isActive = true
            )
        )
    )
}
