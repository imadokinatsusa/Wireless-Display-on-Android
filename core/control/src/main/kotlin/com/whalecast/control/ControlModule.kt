package com.whalecast.control

/**
 * 控制通道模块 —— **模块边界已就位，实现属于工单 11**。
 *
 * 反向控制（接收端触摸/按键事件回传 + 发送端无障碍注入）是受保护能力：
 * 只有已配对且加密的会话才能使用它。因此它刻意不参与切片 01 的环回 demo，
 * 以免在没有加密与配对的前提下先把控制面跑起来。
 */
internal object ControlModulePlaceholder
