package com.wildcreator.sonorus.di

import android.content.Context
import androidx.room.Room
import com.wildcreator.sonorus.data.local.AppDatabase
import com.wildcreator.sonorus.data.local.AppDatabase.Companion.MIGRATION_1_2
import com.wildcreator.sonorus.data.local.AppDatabase.Companion.MIGRATION_2_3
import com.wildcreator.sonorus.data.local.AppDatabase.Companion.MIGRATION_3_4
import com.wildcreator.sonorus.data.local.AppDatabase.Companion.MIGRATION_4_5
import com.wildcreator.sonorus.data.local.AppDatabase.Companion.MIGRATION_5_6
import com.wildcreator.sonorus.data.prefs.SettingsManager
import com.wildcreator.sonorus.data.remote.protocol.AnthropicAdapter
import com.wildcreator.sonorus.data.remote.protocol.GeminiAdapter
import com.wildcreator.sonorus.data.remote.protocol.OpenAiCompatAdapter
import com.wildcreator.sonorus.data.remote.protocol.ProtocolAdapter
import com.wildcreator.sonorus.data.repository.ChatRepositoryImpl
import com.wildcreator.sonorus.data.repository.ProviderRepositoryImpl
import com.wildcreator.sonorus.data.repository.StatsRepositoryImpl
import com.wildcreator.sonorus.data.security.KeystoreSecretStore
import com.wildcreator.sonorus.data.security.SecretStore
import com.wildcreator.sonorus.domain.model.ProviderProtocol
import com.wildcreator.sonorus.domain.repository.ChatRepository
import com.wildcreator.sonorus.domain.repository.ProviderRepository
import com.wildcreator.sonorus.domain.repository.StatsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @OptIn(ExperimentalSerializationApi::class)
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true // 兼容各家提供商的多余字段
        explicitNulls = false
        encodeDefaults = true
    }

    /**
     * 生产安全基线：
     * - 不安装任何日志拦截器（Authorization / x-api-key / x-goog-api-key 一律不上日志）
     * - 明文流量由 network_security_config 在 release 关闭（debug 单独放开）
     * - 禁用 HTTP→HTTPS 降级重定向与跨主机重定向，避免凭据被重定向带走
     */
    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS) // SSE 长连接读超时放宽
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(false) // 端点重定向需显式声明，防止密钥被转发到第三方主机
        .build()

    // ----- 协议适配器多绑定 -----

    @Provides
    @Singleton
    @IntoMap
    @ProtocolKey(ProviderProtocol.OPENAI)
    fun provideOpenAiAdapter(json: Json): ProtocolAdapter = OpenAiCompatAdapter(json)

    @Provides
    @Singleton
    @IntoMap
    @ProtocolKey(ProviderProtocol.ANTHROPIC)
    fun provideAnthropicAdapter(json: Json): ProtocolAdapter = AnthropicAdapter(json)

    @Provides
    @Singleton
    @IntoMap
    @ProtocolKey(ProviderProtocol.GEMINI)
    fun provideGeminiAdapter(json: Json): ProtocolAdapter = GeminiAdapter(json)

    // ----- 安全存储 -----

    @Provides
    @Singleton
    fun provideSecretStore(impl: KeystoreSecretStore): SecretStore = impl

    // ----- 本地持久化 -----

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "sonorus.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            .build()

    @Provides
    fun provideProviderDao(db: AppDatabase) = db.providerDao()

    @Provides
    fun provideModelDao(db: AppDatabase) = db.modelDao()

    @Provides
    fun provideChatDao(db: AppDatabase) = db.chatDao()

    @Provides
    fun provideRequestStatDao(db: AppDatabase) = db.requestStatDao()

    @Provides
    @Singleton
    fun provideSettingsManager(@ApplicationContext context: Context): SettingsManager =
        SettingsManager(context)

    // ----- 协程作用域 -----

    /**
     * 进程级作用域：模型回合从这里启动，离开聊天页（ViewModel 销毁）不会取消正在流式的回答。
     * SupervisorJob —— 一轮失败/取消不能连带停掉后续其他会话的回合。
     */
    @Provides
    @Singleton
    @AppScope
    fun provideAppScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ----- 仓库 -----

    @Provides
    @Singleton
    fun provideProviderRepository(impl: ProviderRepositoryImpl): ProviderRepository = impl

    @Provides
    @Singleton
    fun provideChatRepository(impl: ChatRepositoryImpl): ChatRepository = impl

    @Provides
    @Singleton
    fun provideStatsRepository(impl: StatsRepositoryImpl): StatsRepository = impl
}
