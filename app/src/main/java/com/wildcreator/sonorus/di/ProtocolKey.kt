package com.wildcreator.sonorus.di

import com.wildcreator.sonorus.domain.model.ProviderProtocol
import dagger.MapKey

/** 协议适配器的多绑定键：ProviderApiClient / Repository 以 Map 形式一次性拿到全部适配器 */
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@MapKey
annotation class ProtocolKey(val value: ProviderProtocol)
