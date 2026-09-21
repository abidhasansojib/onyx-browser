package com.onyx.browser.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import android.util.Base64

/**
 * Provides the SQLCipher passphrase for the encrypted Onyx browser database.
 *
 * The 256-bit random key is generated once on first launch and stored inside
 * EncryptedSharedPreferences, which is backed by a hardware-bound AES-256-GCM
 * master key in the Android KeyStore. The key material never appears in
 * plaintext on disk — it is always wrapped by the KeyStore master key.
 */
object SecureDatabaseKeyProvider {

    private const val PREFS_FILE = "onyx_secure_db_prefs"
    private const val KEY_DB_PASSPHRASE = "db_passphrase_v1"
    private const val MASTER_KEY_ALIAS = "onyx_db_master_key"

    @Synchronized
    fun getOrCreatePassphrase(context: Context): ByteArray {
        val masterKey = buildMasterKey(context)
        val encryptedPrefs = EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        val existingKey = encryptedPrefs.getString(KEY_DB_PASSPHRASE, null)
        if (existingKey != null) {
            return Base64.decode(existingKey, Base64.NO_WRAP)
        }

        val newKey = ByteArray(32)
        SecureRandom().nextBytes(newKey)
        val encoded = Base64.encodeToString(newKey, Base64.NO_WRAP)
        encryptedPrefs.edit().putString(KEY_DB_PASSPHRASE, encoded).apply()
        return newKey
    }

    private fun buildMasterKey(context: Context): MasterKey {
        val spec = KeyGenParameterSpec.Builder(
            MASTER_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .build()

        return MasterKey.Builder(context.applicationContext, MASTER_KEY_ALIAS)
            .setKeyGenParameterSpec(spec)
            .build()
    }
}
