package org.fieldnotes.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.fieldnotes.domain.Cache
import org.fieldnotes.domain.obj
import org.fieldnotes.platform.AndroidVault
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.security.KeyStore
import java.util.UUID

class VaultDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var namespace: String
    private lateinit var vault: AndroidVault
    private val folder get() = File(context.noBackupFilesDir, "vault-$namespace")
    @Before fun setup() { namespace = "test-${UUID.randomUUID()}"; vault = AndroidVault(context,namespace) }
    @After fun cleanup() { folder.deleteRecursively(); KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.deleteEntry("org.fieldnotes.vault.$namespace") }
    @Test fun encryptedCacheSurvivesReopenAndDoesNotStorePlaintext() = runBlocking {
        val c=Cache(token="Private test session",keys=obj("private_key" to JsonPrimitive("Private test key")))
        vault.load();vault.save(c)
        assertFalse(File(folder,"workspace.encrypted").readBytes().toString(Charsets.ISO_8859_1).contains("Private test"))
        assertEquals(c,AndroidVault(context,namespace).load())
    }
    @Test fun tamperedCiphertextFailsClosed() = runBlocking {
        vault.load();vault.save(Cache(token="confidential"))
        val file=File(folder,"workspace.encrypted");val bytes=file.readBytes();bytes[20]=(bytes[20].toInt() xor 1).toByte();file.writeBytes(bytes)
        try { vault.load(); fail("Tampering accepted") } catch (_: javax.crypto.AEADBadTagException) { }
    }
    @Test fun lostKeystoreKeyDoesNotCreateAnEmptyReplacement() = runBlocking {
        vault.load();vault.save(Cache(token="retained"));val before=File(folder,"workspace.encrypted").readBytes()
        KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.deleteEntry("org.fieldnotes.vault.$namespace")
        try{AndroidVault(context,namespace).load();fail("Missing key accepted")}catch(_:IllegalStateException){}
        assertArrayEquals(before,File(folder,"workspace.encrypted").readBytes())
    }
    @Test fun cachedImagesAreBoundToAccountScopeAndImageIdentity() = runBlocking {
        vault.load();val a="a".repeat(64);val b="b".repeat(64);val id=UUID.randomUUID().toString();val second=UUID.randomUUID().toString()
        vault.saveImage(a,id,byteArrayOf(1,2,3));assertArrayEquals(byteArrayOf(1,2,3),vault.readImage(a,id));assertNull(vault.readImage(b,id))
        File(folder,"images/$a-$id").copyTo(File(folder,"images/$a-$second"))
        try{vault.readImage(a,second);fail("Image substitution accepted")}catch(_:javax.crypto.AEADBadTagException){}
    }
}
