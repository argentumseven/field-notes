package org.fieldnotes.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.fieldnotes.domain.*

/** Only a nonexportable AES key lives in Keystore. Cache, drafts, sessions and
 * peer private keys are stored in an atomic authenticated encrypted file. */
class AndroidVault(context: Context, namespace: String = "v1") : Vault {
    init { require(namespace.matches(Regex("[a-zA-Z0-9-]{1,80}"))) }
    private val folder = File(context.noBackupFilesDir, "vault-$namespace").apply { mkdirs() }
    private val stateFile = File(folder, "workspace.encrypted")
    private val images = File(folder, "images").apply { mkdirs() }
    private val mutex = Mutex()
    private val alias = "org.fieldnotes.vault.$namespace"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        check(!stateFile.exists() && !File(stateFile.path + ".bak").exists() && images.listFiles().isNullOrEmpty()) { "Local encryption key is missing. Existing data has not been replaced. Restore device access or recover the encrypted state before resetting the app." }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    private fun encrypt(aad: String, clear: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(aad.toByteArray())
        check(cipher.iv.size == 12) { "Unsupported Keystore nonce size." }
        return byteArrayOf(1) + cipher.iv + cipher.doFinal(clear)
    }
    private fun decrypt(aad: String, bytes: ByteArray): ByteArray {
        check(bytes.size >= 29 && bytes[0] == 1.toByte()) { "Invalid local encrypted file." }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            updateAAD(aad.toByteArray()); doFinal(bytes.copyOfRange(13, bytes.size))
        }
    }
    private fun write(file: File, value: ByteArray) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(value); atomic.finishWrite(output) } catch (e: Exception) { atomic.failWrite(output); throw e }
    }
    override suspend fun load(): Cache = withContext(Dispatchers.IO) { mutex.withLock {
        if (!stateFile.exists() && !File(stateFile.path + ".bak").exists()) { key(); Cache() }
        else {
            val clear = decrypt("workspace-v1", AtomicFile(stateFile).readFully())
            try { WireJson.decodeFromString<Cache>(clear.decodeToString()) } finally { clear.fill(0) }
        }
    } }
    override suspend fun save(cache: Cache) { withContext(Dispatchers.IO) { mutex.withLock {
        val clear = WireJson.encodeToString(cache).toByteArray()
        try { check(clear.size <= 32*1024*1024); write(stateFile, encrypt("workspace-v1", clear)) } finally { clear.fill(0) }
    } } }
    override suspend fun clearImages() { withContext(Dispatchers.IO) { mutex.withLock { images.listFiles()?.forEach { it.delete() } } } }
    suspend fun readImage(scope: String, id: String): ByteArray? = withContext(Dispatchers.IO) { mutex.withLock {
        require(id.matches(Regex("[a-fA-F0-9-]{36}"))); require(scope.matches(Regex("[a-f0-9]{64}")))
        val file = File(images, "$scope-$id")
        if (file.exists()) decrypt("image:$scope:$id", AtomicFile(file).readFully()) else null
    } }
    suspend fun saveImage(scope: String, id: String, bytes: ByteArray) { withContext(Dispatchers.IO) { mutex.withLock {
        require(id.matches(Regex("[a-fA-F0-9-]{36}"))); require(scope.matches(Regex("[a-f0-9]{64}"))); require(bytes.size <= 8*1024*1024)
        write(File(images, "$scope-$id"), encrypt("image:$scope:$id", bytes))
        val files = images.listFiles()?.sortedBy { it.lastModified() } ?: emptyList()
        var size = files.sumOf { it.length() }
        for (file in files) { if (size <= 64*1024*1024) break; val length = file.length(); if (file.delete()) size -= length }
    } } }
}
