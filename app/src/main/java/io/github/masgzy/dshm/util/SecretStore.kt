package io.github.masgzy.dshm.util

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 模型 API Key 的本机加密存储：AndroidKeyStore 内生成 AES-256 密钥，
 * GCM 加密后以 Base64 落入普通 SharedPreferences（`dshm_secret`）。
 *
 * ## 为什么不用明文 prefs
 *
 * 参考项目（dphmoblie）把凭据放进"原生侧加密存储"；明文 SharedPreferences
 * 在 root / 备份提取场景下等于裸奔。AndroidKeyStore 的密钥材料不出
 * TEE/Keystore，即使 prefs 文件被拖走也无法还原明文。
 *
 * ## 为什么不用 androidx.security EncryptedSharedPreferences
 *
 * 该库 1.1.0 起整体废弃，且在部分 ROM 上存在 MasterKey 竞态崩溃史；
 * 这里直接走 Keystore + Cipher 的约 40 行实现，零依赖、行为完全可控。
 *
 * 密文格式：`v1:<b64(iv 12B)>:<b64(ciphertext+tag)>`，换密钥或算法时升版本号。
 */
object SecretStore {

  private const val PREFS = "dshm_secret"
  private const val KEYSTORE = "AndroidKeyStore"
  private const val KEY_ALIAS = "dshm_master_aes"
  private const val GCM_TAG_BITS = 128
  private const val IV_BYTES = 12

  private fun prefs(context: Context): SharedPreferences =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  /** 取（或首次生成）Keystore 内的主密钥。 */
  private fun masterKey(): SecretKey {
    val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
    generator.init(
      KeyGenParameterSpec.Builder(
        KEY_ALIAS,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
      )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build(),
    )
    return generator.generateKey()
  }

  /** 加密为可落盘字符串；空输入返回 null（调用方按"未配置"处理）。 */
  fun encrypt(plain: String): String? {
    if (plain.isEmpty()) return null
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, masterKey())
    val iv = cipher.iv
    check(iv.size == IV_BYTES) { "GCM IV 长度异常: ${iv.size}" }
    val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
    val b64e = Base64.getEncoder()
    return "v1:${b64e.encodeToString(iv)}:${b64e.encodeToString(ct)}"
  }

  /** 解密；任何失败（密钥轮换/数据损坏）返回 null，调用方按"未配置"处理。 */
  fun decrypt(stored: String?): String? {
    if (stored.isNullOrEmpty()) return null
    return try {
      val parts = stored.split(':')
      if (parts.size != 3 || parts[0] != "v1") return null
      val b64d = Base64.getDecoder()
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(
        Cipher.DECRYPT_MODE,
        masterKey(),
        GCMParameterSpec(GCM_TAG_BITS, b64d.decode(parts[1])),
      )
      String(cipher.doFinal(b64d.decode(parts[2])), Charsets.UTF_8)
    } catch (t: Throwable) {
      AppLog.log("secret", "解密失败（视为未配置）", t)
      null
    }
  }

  fun put(context: Context, name: String, plain: String) {
    val enc = encrypt(plain.trim())
    prefs(context).edit().apply {
      if (enc == null) remove(name) else putString(name, enc)
      apply()
    }
  }

  fun get(context: Context, name: String): String? =
    decrypt(prefs(context).getString(name, null))

  fun remove(context: Context, name: String) {
    prefs(context).edit().remove(name).apply()
  }
}
