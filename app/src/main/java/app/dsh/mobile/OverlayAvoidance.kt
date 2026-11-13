package app.dsh.mobile

/** 独立避让凭据。clear 后迟到的回调不会影响新凭据。 */
internal class OverlayAvoidance {
    private var nextId = 0L
    private val leases = mutableMapOf<Long, String>()

    val count: Int get() = synchronized(this) { leases.size }
    val hidden: Boolean get() = synchronized(this) { leases.isNotEmpty() }

    @Synchronized fun begin(reason: String): Long = (++nextId).also { leases[it] = reason }

    @Synchronized fun end(id: Long): Boolean = leases.remove(id) != null

    @Synchronized fun clear() { leases.clear() }
}
