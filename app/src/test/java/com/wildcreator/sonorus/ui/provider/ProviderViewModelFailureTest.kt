package com.wildcreator.sonorus.ui.provider

import androidx.lifecycle.SavedStateHandle
import com.wildcreator.sonorus.domain.model.ModelInfo
import com.wildcreator.sonorus.domain.model.Provider
import com.wildcreator.sonorus.domain.model.ProviderCredentials
import com.wildcreator.sonorus.domain.model.ProviderDraft
import com.wildcreator.sonorus.domain.repository.ProviderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 提供商保存/删除的用户可见失败路径。
 *
 * `SecretStore` 刻意用 `commit()` 并在失败时抛（见其注释："失败必须抛出去，让调用方当场告诉他"）。
 * 调用方必须接住并转成可读提示 —— 让异常逃出 viewModelScope 等于直接崩进程，
 * 用户看到的不是"没存上"，而是应用闪退。
 */
class ProviderViewModelFailureTest {

    private class FakeProviderRepository(
        var saveFault: Throwable? = null,
        var deleteFault: Throwable? = null,
        var modelsResult: Result<Int> = Result.success(3)
    ) : ProviderRepository {
        var saveCalls = 0
        var deleteAttempts = 0
        val savedDrafts = mutableListOf<ProviderDraft>()

        override fun observeProviders(): Flow<List<Provider>> = flowOf(emptyList())
        override suspend fun getProvider(id: Long): Provider? = null
        override suspend fun saveProvider(draft: ProviderDraft): Long {
            saveCalls++
            savedDrafts += draft
            saveFault?.let { throw it }
            return 1L
        }
        override suspend fun deleteProvider(id: Long) {
            deleteAttempts++
            deleteFault?.let { throw it }
        }
        override suspend fun conversationCountFor(providerId: Long): Int = 0
        override suspend fun maskedApiKey(id: Long): String? = null
        override suspend fun credentials(id: Long): ProviderCredentials? = null
        override suspend fun testConnection(draft: ProviderDraft): Result<Int> = Result.success(0)
        override fun observeModels(providerId: Long): Flow<List<ModelInfo>> = flowOf(emptyList())
        override suspend fun refreshModels(providerId: Long): Result<Int> = modelsResult
    }

    private val main = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(repository: ProviderRepository) =
        ProviderEditViewModel(SavedStateHandle(), repository)

    private fun ProviderEditViewModel.fillValidForm() {
        onNameChange("本机网关")
        onBaseUrlChange("https://gateway.example.com/v1")
        onApiKeyChange("sk-test-1234")
    }

    @Test
    fun `密钥写入失败时报错而不是崩进程`() = runTest {
        val repository = FakeProviderRepository(saveFault = IllegalStateException("密钥写入失败：provider_1"))
        val viewModel = viewModel(repository).apply { fillValidForm() }

        viewModel.save()

        assertNotNull("必须给用户一条可读的失败提示", viewModel.message)
        assertTrue(
            "提示要说明没保存上，实际为：${viewModel.message}",
            viewModel.message!!.contains("保存") && viewModel.message!!.contains("失败")
        )
        assertFalse("不能假装完成", viewModel.done)
        assertFalse("保存态必须复位", viewModel.saving)
    }

    @Test
    fun `保存成功时清明文密钥并通知返回`() = runTest {
        val repository = FakeProviderRepository()
        val viewModel = viewModel(repository).apply { fillValidForm() }

        viewModel.save()

        assertEquals("", viewModel.apiKey)
        assertTrue(viewModel.done)
        assertNotNull(viewModel.message)
        assertFalse(viewModel.message!!.contains("失败"))
    }

    @Test
    fun `删除提供商时密钥清理失败不崩进程`() = runTest {
        val repository = FakeProviderRepository(deleteFault = IllegalStateException("密钥删除失败：provider_1"))
        val viewModel = ProviderListViewModel(repository)

        viewModel.delete(1L)

        assertEquals(1, repository.deleteAttempts)
        // 异常若逃出 viewModelScope，runTest 会把它作为测试失败抛出：
        // 这条用例能跑完就说明库已删、密钥残留的异常没有崩掉界面
    }
}
