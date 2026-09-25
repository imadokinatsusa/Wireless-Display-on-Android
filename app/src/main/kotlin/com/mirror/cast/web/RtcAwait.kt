package com.mirror.cast.web

import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 把 `SdpObserver` 的四个回调收敛成挂起函数。
 *
 * 媒体栈的协商全是"回调式"的：createOffer/setLocalDescription… 都要求传一个 SdpObserver。
 * 把这些回调包成协程，会话代码才能按顺序线性地写下来，而不是掉进回调地狱。
 *
 * 接口方法清单来自 AAR 字节码实证（`javap org.webrtc.SdpObserver`）：
 * onCreateSuccess / onSetSuccess / onCreateFailure / onSetFailure，四个都是 abstract。
 */
internal suspend fun PeerConnection.awaitOffer(): SessionDescription =
    suspendCancellableCoroutine { continuation ->
        createOffer(
            object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription?) {
                    if (!continuation.isActive) return
                    if (description != null) continuation.resume(description)
                    else continuation.resumeWithException(IllegalStateException("createOffer 返回了空描述"))
                }

                override fun onCreateFailure(error: String?) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException("createOffer 失败：$error"))
                    }
                }

                override fun onSetSuccess() = Unit
                override fun onSetFailure(error: String?) = Unit
            },
            MediaConstraints(),
        )
    }

internal suspend fun PeerConnection.awaitAnswer(): SessionDescription =
    suspendCancellableCoroutine { continuation ->
        createAnswer(
            object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription?) {
                    if (!continuation.isActive) return
                    if (description != null) continuation.resume(description)
                    else continuation.resumeWithException(IllegalStateException("createAnswer 返回了空描述"))
                }

                override fun onCreateFailure(error: String?) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException("createAnswer 失败：$error"))
                    }
                }

                override fun onSetSuccess() = Unit
                override fun onSetFailure(error: String?) = Unit
            },
            MediaConstraints(),
        )
    }

internal suspend fun PeerConnection.setLocalAwait(description: SessionDescription) {
    suspendCancellableCoroutine { continuation ->
        setLocalDescription(
            object : SdpObserver {
                override fun onSetSuccess() {
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onSetFailure(error: String?) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException("应用本地会话描述失败：$error"))
                    }
                }

                override fun onCreateSuccess(description: SessionDescription?) = Unit
                override fun onCreateFailure(error: String?) = Unit
            },
            description,
        )
    }
}

internal suspend fun PeerConnection.setRemoteAwait(description: SessionDescription) {
    suspendCancellableCoroutine { continuation ->
        setRemoteDescription(
            object : SdpObserver {
                override fun onSetSuccess() {
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onSetFailure(error: String?) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException("应用远端会话描述失败：$error"))
                    }
                }

                override fun onCreateSuccess(description: SessionDescription?) = Unit
                override fun onCreateFailure(error: String?) = Unit
            },
            description,
        )
    }
}
