package com.mirror.cast.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 链路候选走查的时序测试。
 *
 * 这些用例锁的**不是**"跑起来不崩"，而是真机上真正出过的那几种回调顺序 ——
 * 尤其是「系统接受了请求，但组一直没成型」。那种情况下如果只认 `onSuccess`，
 * 组就永远建不起来、二维码永远不出现（真机踩过）。
 */
class LinkCandidateWalkTest {

    /** 把 listener 的每一次回调记成一行，用来断言"走过哪些档、什么时候撤期限"。 */
    private class Recorder<T> : LinkCandidateWalk.Listener<T> {
        val log = mutableListOf<String>()

        override fun startCandidate(candidate: T) {
            log += "start:$candidate"
        }

        override fun scheduleTimeout(candidate: T, delayMillis: Long) {
            log += "arm:$candidate"
        }

        override fun cancelTimeout() {
            log += "cancel"
        }

        override fun candidateRejected(candidate: T, reason: String) {
            log += "rejected:$candidate"
        }

        override fun candidateUnsuitable(candidate: T) {
            log += "unsuitable:$candidate"
        }

        override fun allExhausted() {
            log += "exhausted"
        }

        override fun groupFormed(candidate: T) {
            log += "formed:$candidate"
        }
    }

    private fun walk(vararg candidates: Int, timeoutMillis: Long = 4_000L): Pair<LinkCandidateWalk<Int>, Recorder<Int>> {
        val recorder = Recorder<Int>()
        return LinkCandidateWalk(candidates.toList(), timeoutMillis, recorder) to recorder
    }

    /**
     * 头号用例：**系统接受了请求（onSuccess），可组一直没成型。**
     *
     * 这正是"组都没建立起来"那一次的真机现场 —— 只认接受不设期限的话，
     * 走查会停在这一档再也不动，测试在这里变红。
     */
    @Test
    fun `被接受之后必须开始计时 因为接受不等于建成`() {
        val (walk, recorder) = walk(5180, 0)

        walk.begin()
        walk.attemptAccepted()

        assertEquals(listOf("start:5180", "arm:5180"), recorder.log)
    }

    /** 期限到了还没成型 → 换下一档，并且真的去发起下一次尝试。 */
    @Test
    fun `期限到了就换下一档`() {
        val (walk, recorder) = walk(5180, 5745, 0)

        walk.begin()
        walk.attemptAccepted()
        walk.timeout()

        assertEquals(
            listOf("start:5180", "arm:5180", "unsuitable:5180", "start:5745"),
            recorder.log,
        )
    }

    /** 最后一档是"系统默认"，它失败就没得换了 —— 所以不该给它设期限。 */
    @Test
    fun `最后一档被接受时不设期限 因为后面没有可换的了`() {
        val (walk, recorder) = walk(0)

        walk.begin()
        walk.attemptAccepted()

        assertEquals(listOf("start:0"), recorder.log)
    }

    /** 系统明确拒绝（onFailure）也要换下一档 —— 这条路径不依赖任何期限。 */
    @Test
    fun `被系统拒绝就换下一档`() {
        val (walk, recorder) = walk(5180, 5745, 0)

        walk.begin()
        walk.attemptRejected("BUSY")

        assertEquals(
            // `cancel` 也在里面：被拒绝时要先把期限任务撤掉，免得它晚一步再触发换档
            listOf("start:5180", "cancel", "rejected:5180", "start:5745"),
            recorder.log,
        )
    }

    /** 组真建成时：撤掉期限，并且之后哪怕有迟到的期限任务也不该再换档。 */
    @Test
    fun `组建成后撤销期限 迟到的期限不再换档`() {
        val (walk, recorder) = walk(5180, 5745)

        walk.begin()
        walk.attemptAccepted()
        walk.groupFormed()
        walk.timeout()

        assertEquals(
            listOf("start:5180", "arm:5180", "cancel", "formed:5180"),
            recorder.log,
        )
    }

    /** 所有档都试完还是没成 → 明确收场，不能静静地停在那儿。 */
    @Test
    fun `全部候选用尽后报 exhausted`() {
        val (walk, recorder) = walk(5180, 0)

        walk.begin()
        walk.attemptAccepted()
        walk.timeout()
        walk.attemptAccepted()
        walk.timeout()

        assertEquals(
            listOf("start:5180", "arm:5180", "unsuitable:5180", "start:0", "unsuitable:0", "exhausted"),
            recorder.log,
        )
    }

    /** `stop()` 之后任何事件都不再产生副作用（界面离开时会调用它）。 */
    @Test
    fun `stop 之后不再有副作用`() {
        val (walk, recorder) = walk(5180, 5745)

        walk.begin()
        walk.attemptAccepted()
        walk.stop()
        walk.timeout()
        walk.attemptRejected("BUSY")
        walk.groupFormed()

        assertEquals(listOf("start:5180", "arm:5180", "cancel"), recorder.log)
    }

    /** `hasNext` 是给外部决定"要不要设期限"用的，边界要准。 */
    @Test
    fun `hasNext 在最后一档为假`() {
        val (walk, _) = walk(5180, 0)
        walk.begin()
        assertTrue(walk.hasNext)
        walk.attemptRejected("BUSY")
        assertFalse(walk.hasNext)
    }
}
