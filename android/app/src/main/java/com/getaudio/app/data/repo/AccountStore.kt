package com.getaudio.app.data.repo

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import com.getaudio.app.data.remote.AccountSession

/** Session tokens are encrypted with a non-exportable device key; passwords are never stored. */
object AccountStore {
    data class Account(val userId: String, val phone: String, val token: String, val serverUrl: String) {
        val maskedPhone: String get() = phone.takeLast(11).let { it.take(3) + "****" + it.takeLast(4) }
    }
    private const val ALIAS = "getaudio.account.session"
    private val state = MutableStateFlow<Account?>(null)
    val current = state.asStateFlow()
    private lateinit var file: File

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun initialize(context: Context) {
        file = File(context.filesDir, "account-session.enc")
        state.value = runCatching {
            if (!file.exists()) return@runCatching null
            val data = JSONObject(String(decrypt(file.readBytes()), Charsets.UTF_8))
            Account(data.getString("user_id"), data.getString("phone"), data.getString("token"), data.getString("server"))
        }.getOrNull()
    }

    fun save(session: AccountSession, baseUrl: String) {
        val account = Account(session.userId, session.phone, session.sessionToken, baseUrl)
        val data = JSONObject().put("user_id", account.userId).put("phone", account.phone)
            .put("token", account.token).put("server", baseUrl).toString().toByteArray()
        val encrypted = encrypt(data)
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeBytes(encrypted)
        check(temp.renameTo(file)) { "无法保存账号登录状态" }
        state.value = account
    }

    internal fun encrypt(bytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(bytes)
    }

    internal fun decrypt(bytes: ByteArray): ByteArray {
        require(bytes.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size))
    }

    fun clear() {
        file.delete()
        state.value = null
    }

    fun forServer(baseUrl: String): Account? = state.value?.takeIf { it.serverUrl == baseUrl }

    fun sessionFor(url: HttpUrl): String? = state.value?.let { account ->
        val base = account.serverUrl.toHttpUrl()
        if (isCredentialOrigin(url, base) && url.encodedPath.startsWith(base.encodedPath)) account.token else null
    }

    fun requireSecureServer(baseUrl: String) {
        val url = baseUrl.toHttpUrl()
        require(url.isHttps || (url.host in listOf("127.0.0.1", "localhost") && com.getaudio.app.BuildConfig.DEBUG)) {
            "账号登录需要 HTTPS 服务器地址"
        }
    }
}
