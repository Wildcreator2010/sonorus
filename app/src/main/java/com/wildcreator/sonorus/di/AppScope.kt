package com.wildcreator.sonorus.di

import javax.inject.Qualifier

/**
 * 进程级协程作用域。
 *
 * 用于「离开聊天页后仍要继续到完成」的模型回合：AgentExecutor 是 @Singleton，
 * 但 ViewModel 一销毁 viewModelScope 就取消，SSE 流会被掐断在半截回答处。
 */
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Qualifier
annotation class AppScope
