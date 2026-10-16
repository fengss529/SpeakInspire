package com.hatio.speakinspire.data.repository

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.hatio.speakinspire.data.local.ProviderDao
import com.hatio.speakinspire.data.local.ProviderEntity
import com.hatio.speakinspire.data.model.ProviderConfig
import com.hatio.speakinspire.data.remote.Providers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 服务商配置仓库。
 *
 * ## 为什么要分开存
 *
 * **key 不能进 Room**。数据库文件是明文的，adb backup 就能拖出来。
 * 所以配置分两处：
 * - Room：base_url / model / thinking 开关 /是否启用（不含敏感信息）
 * - EncryptedSharedPreferences：仅 API key
 *
 * 这样即使数据库泄露，key 也是安全的。
 */
@Singleton
class ProviderRepository @Inject constructor(
    private val providerDao: ProviderDao,
    @ApplicationContext private val context: Context,
) {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** 当前生效的配置。key 从加密存储取 */
    suspend fun activeConfig(): ProviderConfig? = withContext(Dispatchers.IO) {
        val entity = providerDao.findActive() ?: return@withContext null
        val preset = Providers.byId(entity.providerId)
        val key = prefs.getString(keyPrefName(entity.providerId), "") ?: ""
        if (key.isBlank()) {
            // 选了服务商但没填 key——视为未配置
            null
        } else {
            ProviderConfig(
                providerId = entity.providerId,
                baseUrl = entity.baseUrl.ifBlank { preset.baseUrl },
                apiKey = key,
                model = entity.model,
                thinkingDisabled = entity.thinkingDisabled,
            )
        }
    }

    /** 选中的服务商（可能还没填 key）——设置页要显示这个 */
    suspend fun selectedProviderId(): String? = withContext(Dispatchers.IO) {
        providerDao.findActive()?.providerId
    }

    suspend fun hasAnyKey(): Boolean = withContext(Dispatchers.IO) {
        providerDao.findAll().any { !prefs.getString(keyPrefName(it.providerId), "").isNullOrBlank() }
    }

    suspend fun saveConfig(cfg: ProviderConfig) = withContext(Dispatchers.IO) {
        // 切active 前先清掉旧的，保证只有一个生效
        providerDao.clearActive()
        providerDao.upsert(
            ProviderEntity(
                providerId = cfg.providerId,
                baseUrl = cfg.baseUrl,
                model = cfg.model,
                thinkingDisabled = cfg.thinkingDisabled,
                active = true,
                updatedAt = System.currentTimeMillis(),
            )
        )
        if (cfg.apiKey.isNotBlank()) {
            prefs.edit().putString(keyPrefName(cfg.providerId), cfg.apiKey).apply()
        }
    }

    suspend fun saveKey(providerId: String, apiKey: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(keyPrefName(providerId), apiKey).apply()
    }

    suspend fun readKey(providerId: String): String? = withContext(Dispatchers.IO) {
        prefs.getString(keyPrefName(providerId), "")
    }

    suspend fun selectProvider(providerId: String) = withContext(Dispatchers.IO) {
        providerDao.clearActive()
        val preset = Providers.byId(providerId)
        providerDao.upsert(
            ProviderEntity(
                providerId = providerId,
                baseUrl = preset.baseUrl,
                model = preset.model,
                thinkingDisabled = preset.thinkingDisabled,
                active = true,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    /** 拿一份配置用于连接测试——允许 key 为空，这样「先测再存」可行 */
    suspend fun configForTest(providerId: String): ProviderConfig = withContext(Dispatchers.IO) {
        val preset = Providers.byId(providerId)
        val saved = providerDao.findAll().firstOrNull { it.providerId == providerId }
        ProviderConfig(
            providerId = providerId,
            baseUrl = saved?.baseUrl?.ifBlank { preset.baseUrl } ?: preset.baseUrl,
            apiKey = prefs.getString(keyPrefName(providerId), "").orEmpty(),
            model = saved?.model?.ifBlank { preset.model } ?: preset.model,
            thinkingDisabled = saved?.thinkingDisabled ?: preset.thinkingDisabled,
        )
    }

    companion object {
        private const val SECURE_PREFS = "speakinspire_secure"

        /** 每个服务商一个 key 槽位——用户可能同时填两家的 key 切换用 */
        private fun keyPrefName(providerId: String) = "api_key_$providerId"
    }
}