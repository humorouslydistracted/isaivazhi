package com.isaivazhi.app.engine

object SoftRefreshPlanner {
    data class Zones<T>(
        val frozen: List<T>,
        val stable: List<T>,
        val fluid: List<T>,
    ) {
        val hasRefreshableTail: Boolean
            get() = stable.isNotEmpty() || fluid.isNotEmpty()
    }

    fun <T> split(
        upcoming: List<T>,
        frozenZoneSize: Int = 5,
        stableZoneEnd: Int = 15,
    ): Zones<T> {
        if (upcoming.size <= frozenZoneSize) {
            return Zones(upcoming, emptyList(), emptyList())
        }
        val frozenEnd = minOf(frozenZoneSize, upcoming.size)
        val stableEnd = minOf(stableZoneEnd, upcoming.size)
        return Zones(
            frozen = upcoming.subList(0, frozenEnd),
            stable = upcoming.subList(frozenEnd, stableEnd),
            fluid = if (upcoming.size > stableEnd) {
                upcoming.subList(stableEnd, upcoming.size)
            } else {
                emptyList()
            },
        )
    }
}
