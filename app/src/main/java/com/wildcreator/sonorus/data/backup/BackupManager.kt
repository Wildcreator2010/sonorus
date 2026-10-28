package com.wildcreator.sonorus.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.wildcreator.sonorus.data.local.AppDatabase
import com.wildcreator.sonorus.data.local.ConversationEntity
import com.wildcreator.sonorus.data.local.MessageEntity
import com.wildcreator.sonorus.data.local.ProviderEntity
import com.wildcreator.sonorus.data.prefs.SettingsManager
import com.wildcreator.sonorus.domain.agent.ThinkingLevel
import com.wildcreator.sonorus.domain.model.BackupCipher
import com.wildcreator.sonorus.domain.model.BackupConversation
import com.wildcreator.sonorus.domain.model.BackupFile
import com.wildcreator.sonorus.domain.model.BackupMessage
import com.wildcreator.sonorus.domain.model.BackupProvider
import com.wildcreator.sonorus.domain.model.BackupSchema
import com.wildcreator.sonorus.domain.model.BackupSettings
import com.wildcreator.sonorus.domain.model.ProviderDraft
import com.wildcreator.sonorus.domain.model.ProviderProtocol
import com.wildcreator.sonorus.domain.repository.ProviderRepository
import com.wildcreator.sonorus.ui.theme.FontScale
import com.wildcreator.sonorus.ui.theme.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 无损备份与恢复：把整机内容写进单个口令加密文件，换设备后合并去重地导回来。
 *
 * 与 [com.wildcreator.sonorus.domain.model.ConversationExporter] 的边界要分清：
 * 导出是给人看的有损文本（图片只留字节数、不含工具调用），备份是给机器恢复的无损容器，
 * 两者不能互相替代。
 *
 * 密钥：备份含 API Key，但整个 JSON 处在口令派生的 AES-GCM 之下，明文头只有 magic/版本/salt/iv。
 * 口令本身永不落盘，恢复时必须现场输入。
 */
@Singleton
class BackupManager @Inject constructor(
    private val db: AppDatabase,
    private val providerRepository: ProviderRepository,
    private val settings: SettingsManager,
    @ApplicationContext private val context: Context
) {

    data class ExportSummary(val conversations: Int, val messages: Int, val providers: Int, val bytes: Int)

    /** 恢复前的预告。跳过数与挂靠不上的会话数都要显式告诉用户，不能默默少掉 */
    data class ImportPlan(
        val conversationsToInsert: Int,
        val conversationsSkipped: Int,
        val messagesToInsert: Int,
        val providersToAdd: Int,
        val providersToFillKey: Int,
        val conversationsUnmatched: Int
    )

    data class ImportResult(
        val conversations: Int,
        val messages: Int,
        val providersAdded: Int,
        val keysFilled: Int,
        val unmatchedSkipped: Int
    )

    fun suggestedFileName(): String {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return "sonorus-backup-$day.$EXTENSION"
    }

    /**
     * 加密写入 [target]（SAF 返回的 uri）。
     * 失败直接抛出交给 UI 提示：留下半截文件比什么都没留更糟，所以不做重试、也不改名续写。
     */
    suspend fun exportTo(target: Uri, passphrase: String): ExportSummary {
        val file = collect()
        val container = BackupCipher.encrypt(BackupSchema.encode(file).toByteArray(Charsets.UTF_8), passphrase)

        val written = context.contentResolver.openOutputStream(target, "w")?.use { out ->
            out.write(container)
            out.flush()
            container.size
        } ?: throw IllegalStateException("无法写入所选位置")

        return ExportSummary(
            conversations = file.conversations.size,
            messages = file.conversations.sumOf { it.messages.size },
            providers = file.providers.size,
            bytes = written
        )
    }

    /** 读取并解密。口令错误或文件被改都会抛 [BackupCipher.DecryptionFailed] */
    suspend fun load(source: Uri, passphrase: String): BackupFile {
        val container = context.contentResolver.openInputStream(source)?.use { it.readBytes() }
            ?: throw IllegalStateException("无法读取所选文件")
        return BackupSchema.decode(BackupCipher.decrypt(container, passphrase).toString(Charsets.UTF_8))
    }

    suspend fun plan(file: BackupFile): ImportPlan {
        val local = db.providerDao().observeAll().first()
        val knownProviders = local.map { identity(it.name, it.baseUrl) }.toSet()
        val existing = existingFingerprints()

        // 备份里没有、本机也没有的提供商：这类会话无处挂靠
        val backupProviders = file.providers.map { identity(it.name, it.baseUrl) }.toSet()

        var insert = 0
        var skip = 0
        var messages = 0
        var unmatched = 0

        file.conversations.forEach { conv ->
            val key = identity(conv.providerName, conv.providerBaseUrl)
            when {
                conv.fingerprint in existing -> skip++
                key !in knownProviders && key !in backupProviders -> unmatched++
                else -> {
                    insert++
                    messages += conv.messages.size
                }
            }
        }

        val byIdentity = local.associateBy { identity(it.name, it.baseUrl) }
        val fillKey = file.providers.count { p ->
            val localRow = byIdentity[identity(p.name, p.baseUrl)]
            localRow != null && !p.apiKey.isNullOrBlank() && providerRepository.credentials(localRow.id) == null
        }

        return ImportPlan(
            conversationsToInsert = insert,
            conversationsSkipped = skip,
            messagesToInsert = messages,
            providersToAdd = file.providers.count { identity(it.name, it.baseUrl) !in knownProviders },
            providersToFillKey = fillKey,
            conversationsUnmatched = unmatched
        )
    }

    /**
     * 执行恢复。
     *
     * 读全部在事务外、写全部在事务内：Room 的 Flow 查询（`observeAll` / `observeConversations`）
     * 在事务进行期间可能等待本次提交才发结果，混在 [withTransaction] 里等于自己等自己，
     * 恢复界面会永久卡住。所以先把"要写什么"全部算出来，再一次性落库。
     */
    suspend fun apply(file: BackupFile): ImportResult {
        val localProviders = db.providerDao().observeAll().first()
        val existing = existingFingerprints()
        val actions = planProviderActions(file.providers, localProviders)

        val result = db.withTransaction {
            val ids = HashMap<String, Long>()
            var added = 0
            var keysFilled = 0

            actions.forEach { action ->
                when (action) {
                    is ProviderAction.Bind -> ids[action.identity] = action.id

                    is ProviderAction.FillKey -> {
                        providerRepository.saveProvider(action.draft)
                        ids[action.identity] = action.draft.id
                        keysFilled++
                    }

                    is ProviderAction.Create -> {
                        ids[action.identity] = providerRepository.saveProvider(action.draft)
                        added++
                    }
                }
            }

            var conversations = 0
            var messages = 0
            var unmatched = 0

            file.conversations.forEach { backup ->
                val key = identity(backup.providerName, backup.providerBaseUrl)
                val providerId = ids[key]
                // 挂靠不上就跳过：硬塞给某个默认提供商会让用户以为会话坏了，实际是模型不对
                if (providerId == null || backup.fingerprint in existing) {
                    if (providerId == null) unmatched++
                    return@forEach
                }

                val newId = db.chatDao().upsertConversation(backup.toEntity(providerId))
                backup.messages.forEach { db.chatDao().insertMessage(it.toEntity(newId)) }
                conversations++
                messages += backup.messages.size
            }

            ImportResult(conversations, messages, added, keysFilled, unmatched)
        }

        // 偏好设置在 DataStore 里，不参与 Room 事务：放在提交之后，失败也不该回滚已恢复的内容
        file.settings?.let { applySettings(it) }
        return result
    }

    /**
     * 备份里的提供商怎么落到本机：同 name+baseUrl 视为同一个。
     * 已存在的行**不改元数据**，只在"本机没密钥而备份有"时补密钥 ——
     * 用户在当前设备上填好的密钥永远优先，恢复不该把它覆盖掉。
     */
    private suspend fun planProviderActions(
        backups: List<BackupProvider>,
        local: List<ProviderEntity>
    ): List<ProviderAction> {
        val byIdentity = local.associateBy { identity(it.name, it.baseUrl) }

        return backups.mapNotNull { p ->
            val protocol = runCatching { ProviderProtocol.valueOf(p.protocol) }.getOrNull() ?: return@mapNotNull null
            val key = identity(p.name, p.baseUrl)
            val existing = byIdentity[key]

            when {
                existing == null -> ProviderAction.Create(
                    key,
                    ProviderDraft(
                        id = 0, // 新行：id 交给本机数据库自增，备份里的数字 id 属于源设备
                        name = p.name.trim(),
                        baseUrl = p.baseUrl.trim(),
                        protocol = protocol,
                        enabled = p.enabled,
                        apiKey = p.apiKey?.takeIf { it.isNotBlank() }
                    )
                )

                p.apiKey.isNullOrBlank() || providerRepository.credentials(existing.id) != null ->
                    ProviderAction.Bind(key, existing.id)

                else -> ProviderAction.FillKey(key, existing.toDraft(apiKey = p.apiKey))
            }
        }
    }

    private sealed class ProviderAction {
        /** 本机已有且密钥无需改动 */
        data class Bind(val identity: String, val id: Long) : ProviderAction()

        /** 本机已有但没有密钥，备份里有 */
        data class FillKey(val identity: String, val draft: ProviderDraft) : ProviderAction()

        /** 本机没有这个提供商 */
        data class Create(val identity: String, val draft: ProviderDraft) : ProviderAction()
    }

    private suspend fun collect(): BackupFile {
        val providerRows = db.providerDao().observeAll().first()
        val providers = providerRows.map { entity ->
            BackupProvider(
                name = entity.name,
                baseUrl = entity.baseUrl,
                protocol = entity.protocol,
                enabled = entity.enabled,
                apiKey = providerRepository.credentials(entity.id)?.apiKey
            )
        }
        // 明文预算：整个备份要一次性变成 String → ByteArray → 密文多份拷贝，
        // 没有闸门在手机上就是 OOM。超限直接拒绝并说清怎么办，不做半份备份。
        var budget = MAX_PLAIN_CHARS
        val conversations = db.chatDao().observeConversations().first().map { conv ->
            val rows = db.chatDao().getMessages(conv.id)
            val cost = rows.sumOf { row ->
                row.content.length + (row.imagesJson?.length ?: 0) +
                    (row.filesJson?.length ?: 0) +
                    (row.toolCallsJson?.length ?: 0) + (row.versionsJson?.length ?: 0)
            } + conv.title.length + conv.systemPrompt.length
            budget -= cost
            if (budget < 0) {
                throw IllegalStateException(
                    "备份内容超过约 ${MAX_PLAIN_CHARS / (1024 * 1024)} MB（多为图片 base64 所致），" +
                        "已中止。请先删除部分含图片的会话，或改用「导出会话」逐条保存。"
                )
            }

            val rowEntity = db.providerDao().getById(conv.providerId)
            BackupConversation(
                title = conv.title,
                titleAuto = conv.titleAuto,
                providerName = rowEntity?.name ?: ORPHANED_PROVIDER,
                providerBaseUrl = rowEntity?.baseUrl ?: "",
                modelId = conv.modelId,
                systemPrompt = conv.systemPrompt,
                toolsEnabled = conv.toolsEnabled,
                thinkingLevel = conv.thinkingLevel,
                contextSummary = conv.contextSummary,
                summaryThroughMessageId = conv.summaryThroughMessageId,
                createdAt = conv.createdAt,
                updatedAt = conv.updatedAt,
                messages = rows.map { it.toBackup() }
            )
        }

        return BackupFile(
            createdAt = System.currentTimeMillis(),
            appVersion = versionName(),
            providers = providers,
            conversations = conversations,
            settings = BackupSettings(
                contextLimit = settings.contextLimit.first(),
                defaultSystemPrompt = settings.defaultSystemPrompt.first(),
                thinkingLevel = settings.thinkingLevel.first().name,
                themeMode = settings.themeMode.first().name,
                fontScale = settings.fontScale.first().name
            )
        )
    }

    private suspend fun existingFingerprints(): Set<String> {
        val chatDao = db.chatDao()
        return chatDao.observeConversations().first().map {
            BackupFile.fingerprint(it.title, it.createdAt, chatDao.countMessages(it.id))
        }.toSet()
    }

    private suspend fun applySettings(s: BackupSettings) {
        settings.saveContextLimit(s.contextLimit.coerceAtLeast(0))
        settings.saveDefaultSystemPrompt(s.defaultSystemPrompt)
        settings.saveThinkingLevel(
            runCatching { ThinkingLevel.valueOf(s.thinkingLevel) }.getOrDefault(ThinkingLevel.OFF)
        )
        settings.saveThemeMode(
            runCatching { ThemeMode.valueOf(s.themeMode) }.getOrDefault(ThemeMode.SYSTEM)
        )
        settings.saveFontScale(
            runCatching { FontScale.valueOf(s.fontScale) }.getOrDefault(FontScale.DEFAULT)
        )
    }

    private fun versionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    /** 提供商身份 = 名称 + 端点，两端都 trim，避免尾空格让"同一个提供商"变成两条 */
    private fun identity(name: String, baseUrl: String) = "${name.trim()}|${baseUrl.trim()}"

    private fun ProviderEntity.toDraft(apiKey: String?) = ProviderDraft(
        id = id,
        name = name,
        baseUrl = baseUrl,
        protocol = runCatching { ProviderProtocol.valueOf(protocol) }.getOrDefault(ProviderProtocol.OPENAI),
        enabled = enabled,
        apiKey = apiKey
    )

    private fun BackupConversation.toEntity(providerId: Long) = ConversationEntity(
        id = 0, // 强制新生成 id：备份里的数字 id 属于源设备，照抄会撞上本机已有行
        title = title,
        titleAuto = titleAuto,
        providerId = providerId,
        modelId = modelId,
        systemPrompt = systemPrompt,
        toolsEnabled = toolsEnabled,
        thinkingLevel = thinkingLevel,
        // Message ids are device-local; restore raw messages and rebuild the summary on this device.
        contextSummary = null,
        summaryThroughMessageId = 0,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun BackupMessage.toEntity(conversationId: Long) = MessageEntity(
        id = 0,
        conversationId = conversationId,
        role = role,
        content = content,
        imagesJson = imagesJson,
        filesJson = filesJson,
        toolCallsJson = toolCallsJson,
        toolCallId = toolCallId,
        toolName = toolName,
        versionsJson = versionsJson,
        activeVersion = activeVersion,
        createdAt = createdAt
    )

    private fun MessageEntity.toBackup() = BackupMessage(
        role = role,
        content = content,
        imagesJson = imagesJson,
        filesJson = filesJson,
        toolCallsJson = toolCallsJson,
        toolCallId = toolCallId,
        toolName = toolName,
        versionsJson = versionsJson,
        activeVersion = activeVersion,
        createdAt = createdAt
    )

    private companion object {
        const val EXTENSION = "sonorusbackup"
        const val ORPHANED_PROVIDER = "(已删除的提供商)"

        /** 明文字符上限：约 100MB 字符，留出 String→ByteArray→密文的多份拷贝余量 */
        const val MAX_PLAIN_CHARS = 100 * 1024 * 1024
    }
}
