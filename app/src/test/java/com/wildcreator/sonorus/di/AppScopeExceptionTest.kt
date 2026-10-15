package com.wildcreator.sonorus.di

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 进程级回合作用域必须自带异常兜底。
 *
 * 回合从 [AppModule.provideAppScope] 启动（离开聊天页不能取消正在流式的回答）。
 * SupervisorJob 只保证"一轮失败不牵连别轮"，逃逸的异常仍会走线程默认处理器 ——
 * 用户看到的就是闪退，且 `running` 标志卡在 true，之后所有会话都发不出消息。
 */
class AppScopeExceptionTest {

    @Test
    fun `逃逸出回合的异常被作用域兜住且不逃到线程`() {
        val scope = AppModule.provideAppScope()

        assertNotNull(
            "runScope 缺 CoroutineExceptionHandler：逃逸异常会直接崩掉进程",
            scope.coroutineContext[CoroutineExceptionHandler.Key]
        )

        val leaked = CountDownLatch(1)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> leaked.countDown() }
        try {
            val job = scope.launch { throw IllegalStateException("回合里逃逸的异常") }
            runBlocking { job.join() }

            assertFalse("异常逃到了线程默认处理器 = 进程崩溃", leaked.await(500, TimeUnit.MILLISECONDS))
            assertTrue("兜住之后作用域必须还能继续用", scope.isActive)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun `一轮失败不能连带停掉后续回合`() {
        val scope = AppModule.provideAppScope()

        runBlocking {
            scope.launch { throw IllegalStateException("上一轮炸了") }.join()
            var secondRan = false
            scope.launch { secondRan = true }.join()
            assertTrue("SupervisorJob 语义：前一轮的失败不该让后一轮跑不起来", secondRan)
        }
        assertTrue(scope.coroutineContext.job.isActive)
    }
}
