package com.mirror.cast.discovery

/**
 * 「按优先级依次换链路候选」的状态机 —— **纯逻辑，不依赖任何 Android API**。
 *
 * 为什么值得单独抽出来：这段逻辑在真机上出过的每一次错，都错在**时序**上 ——
 * 系统会欣然接受一个请求（`onSuccess`）却永远建不出组，也会在期限的最后一刻才建出来。
 * 而它埋在 `WifiP2pManager` 的回调里时，只能靠真机一遍遍试；
 * 抽出来之后，**任意回调顺序都能在 JVM 上按毫秒重放**，包括"接受之后就毫无下文"这一种。
 *
 * 另一条真机教训：系统拒绝候选时回的是 **`onFailure(reason)`，不是抛异常**。
 * 所以"换下一档"必须建立在回调之上，而不是 `try/catch` 之上。
 */
class LinkCandidateWalk<T>(
    /** 按优先级排好的候选；最后一项是"系统默认"，它保证至少有一条退路。 */
    private val candidates: List<T>,
    /** 一个候选从"被接受"到"组真的成型"最多等多久。 */
    private val timeoutMillis: Long,
    private val listener: Listener<T>,
) {

    /** 与外部世界的全部交互面；Android 侧在这里切到 `WifiP2pManager`。 */
    interface Listener<T> {
        /** 去真正发起一次尝试（Android 侧：`createGroup`）。 */
        fun startCandidate(candidate: T)

        /** 给这一档设一个期限，到点请调 [timeout]。 */
        fun scheduleTimeout(candidate: T, delayMillis: Long)

        /** 撤销还没到点的期限（组建成 / 换档 / 收尾时都要）。 */
        fun cancelTimeout()

        /** 这一档被系统明确拒绝。 */
        fun candidateRejected(candidate: T, reason: String)

        /** 这一档被接受、但没能建起组，正准备换下一档。 */
        fun candidateUnsuitable(candidate: T)

        /** 全部候选都用完了，还是没建成。 */
        fun allExhausted()

        /** 组真的建起来了。 */
        fun groupFormed(candidate: T)
    }

    private var index = -1
    private var current: T? = null
    private var finished = false

    /** 后面还有没有可换的档。最后一档用不到期限 —— 它失败也没得换。 */
    val hasNext: Boolean get() = index + 1 < candidates.size

    fun begin() {
        if (finished || candidates.isEmpty()) return
        place(0)
    }

    /**
     * 外部报告：这一档**被系统接受了**。
     *
     * ⚠️ 这里就是那个真机 bug 的现场。只走到这一步就当作"成了"，后果是：
     * 系统接受一个它其实建不出来的频段 → 组永远没有 → 上层永远等不到地址 →
     * 二维码永远不出现，而状态行上还写着"建组已发出"。
     */
    fun attemptAccepted() {
        if (finished) return
        val candidate = current ?: return
        // ⚠️ 接受 ≠ 建成：系统会接受一个它其实建不出来的频段，然后悄无声息地失败。
        // 所以这里必须**开始计时**，到点还没成型就换下一档。
        // 最后一档不设期限 —— 它后面已经没有可换的了。
        if (hasNext) {
            listener.scheduleTimeout(candidate, timeoutMillis)
        }
    }

    /** 外部报告：这一档被系统拒绝了。 */
    fun attemptRejected(reason: String) {
        if (finished) return
        val candidate = current ?: return
        listener.cancelTimeout()
        listener.candidateRejected(candidate, reason)
        advance()
    }

    /** 外部报告：期限到了，组还没成型。 */
    fun timeout() {
        if (finished) return
        val candidate = current ?: return
        listener.candidateUnsuitable(candidate)
        advance()
    }

    /** 外部报告：组真的建起来了。 */
    fun groupFormed() {
        if (finished) return
        val candidate = current ?: return
        finished = true
        listener.cancelTimeout()
        listener.groupFormed(candidate)
    }

    /** 收尾（界面离开 / 会话结束）：不再接受任何事件。 */
    fun stop() {
        finished = true
        listener.cancelTimeout()
    }

    private fun place(next: Int) {
        if (next >= candidates.size) {
            finished = true
            listener.allExhausted()
            return
        }
        index = next
        current = candidates[next]
        listener.startCandidate(candidates[next])
    }

    private fun advance() = place(index + 1)
}
