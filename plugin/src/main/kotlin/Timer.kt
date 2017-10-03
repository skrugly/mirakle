class Timer {

    private val MILLIS_PER_SECOND = 1000
    private val MILLIS_PER_MINUTE = 60 * MILLIS_PER_SECOND
    private val MILLIS_PER_HOUR = 60 * MILLIS_PER_MINUTE

    private val startTime: Long = getCurrentTime()

    val elapsed: String = formatDurationVerbose(getCurrentTime() - startTime)

    private fun getCurrentTime(): Long {
        return System.currentTimeMillis()
    }

    private fun formatDurationVerbose(durationMillis: Long): String {
        val result = StringBuilder()
        if (durationMillis > MILLIS_PER_HOUR) {
            result.append(durationMillis / MILLIS_PER_HOUR).append(" hrs ")
        }
        if (durationMillis > MILLIS_PER_MINUTE.toLong()) {
            result.append(durationMillis % MILLIS_PER_HOUR / MILLIS_PER_MINUTE).append(" mins ")
        }
        result.append(durationMillis % MILLIS_PER_MINUTE / 1000.0).append(" secs")
        return result.toString()
    }
}