package io.artex.mobile.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SessionStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("artex_mobile", Context.MODE_PRIVATE)
    private val wallpaper = File(context.filesDir, "wallpaper.jpg")

    val baseUrl: String get() = prefs.getString("base_url", "").orEmpty()
    val wallpaperDim: Float get() = prefs.getFloat("wallpaper_dim", 0.68f)

    fun setWallpaperDim(value: Float) {
        prefs.edit().putFloat("wallpaper_dim", value.coerceIn(0.65f, 0.95f)).apply()
    }

    fun token(): String? {
        return try {
            val iv = prefs.getString("token_iv", null) ?: return null
            val encoded = prefs.getString("token_ciphertext", null) ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            clearSession()
            null
        }
    }

    fun saveSession(url: String, token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("base_url", url)
            .putString("token_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("token_ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun clearSession() {
        prefs.edit().remove("token_iv").remove("token_ciphertext").apply()
    }

    fun loadWallpaper(): Bitmap? = if (wallpaper.exists()) BitmapFactory.decodeFile(wallpaper.path) else null

    suspend fun saveWallpaper(uri: Uri): Bitmap = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("无法读取这张图片")
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalArgumentException("图片格式不受支持")
        var sample = 1
        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 2400) sample *= 2
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalArgumentException("无法解码这张图片")
        val pending = File(context.filesDir, "wallpaper.pending")
        try {
            pending.outputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 86, output)) {
                    throw IllegalStateException("保存背景失败")
                }
            }
            if (!pending.renameTo(wallpaper)) {
                pending.copyTo(wallpaper, overwrite = true)
                pending.delete()
            }
        } finally {
            pending.delete()
        }
        bitmap
    }

    fun clearWallpaper() { wallpaper.delete() }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("artex_mobile_session", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder("artex_mobile_session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    companion object {
        fun normalizeServerAddress(input: String): String {
            val value = input.trim().trimEnd('/')
            require(value.isNotEmpty()) { "请输入服务器 IP 或域名" }
            val candidate = if (value.contains("://")) value else "http://$value"
            val uri = try { URI(candidate) } catch (_: Exception) { throw IllegalArgumentException("服务器地址格式不正确") }
            require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()) {
                "请输入有效的 http 或 https 地址"
            }
            require(uri.userInfo == null && uri.query == null && uri.fragment == null &&
                (uri.path.isNullOrEmpty() || uri.path == "/") && uri.port != 0 && uri.port <= 65535) {
                "请只填写服务器地址和端口"
            }
            return "${uri.scheme.lowercase()}://${uri.rawAuthority}"
        }
    }
}
