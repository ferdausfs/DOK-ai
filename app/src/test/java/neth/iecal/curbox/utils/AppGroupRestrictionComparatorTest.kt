package neth.iecal.curbox.utils

import neth.iecal.curbox.data.models.AppGroup
import neth.iecal.curbox.data.models.AppGroupConfig
import neth.iecal.curbox.data.models.AppTimeConfig
import neth.iecal.curbox.data.models.AppUsageConfig
import neth.iecal.curbox.data.models.TimeInterval
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppGroupRestrictionComparatorTest {
    private val oldGroup = group(startHour = 9, usageMinutes = 60)

    @Test
    fun lowerUsageAllowanceIsStricter() {
        assertTrue(
            RestrictionComparator.appGroups(
                listOf(oldGroup),
                listOf(group(startHour = 9, usageMinutes = 30))
            )
        )
    }

    @Test
    fun shorterActiveScheduleIsWeaker() {
        assertFalse(
            RestrictionComparator.appGroups(
                listOf(oldGroup),
                listOf(group(startHour = 10, usageMinutes = 60))
            )
        )
    }

    @Test
    fun higherUsageAllowanceIsWeaker() {
        assertFalse(
            RestrictionComparator.appGroups(
                listOf(oldGroup),
                listOf(group(startHour = 9, usageMinutes = 90))
            )
        )
    }

    private fun group(startHour: Int, usageMinutes: Long) = AppGroup(
        id = "group",
        selectedPackages = listOf("example.app"),
        config = AppGroupConfig(
            schedule = AppTimeConfig(
                everydayIntervals = mutableListOf(TimeInterval(startHour, 0, 17, 0))
            ),
            usage = AppUsageConfig(uniformLimit = usageMinutes)
        ),
        isActive = true
    )
}
