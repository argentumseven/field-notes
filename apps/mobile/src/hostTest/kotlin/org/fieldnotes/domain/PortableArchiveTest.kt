package org.fieldnotes.domain

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.fieldnotes.platform.PortableArchive
import org.fieldnotes.platform.PortableCipher
import kotlin.test.*

class PortableArchiveTest {
    private val pass="A portable café 🌙 with punctuation!".toCharArray()
    private val image="12345678-1234-1234-1234-123456789abc"
    private fun archive(wrongHash:Boolean=false,extra:String?=null):ByteArray {
        val bytes=ByteArray(150000){(it%251).toByte()}
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        val manifest=obj("format" to "org.fieldnotes.archive".j(),"version" to 1.j(),"source" to "12345678-1234-1234-1234-123456789aaa".j(),"generation" to 1.j(),"records" to JsonArray(emptyList()),"people" to JsonArray(emptyList()),"media" to JsonArray(listOf(obj("id" to image.j(),"size" to bytes.size.j(),"sha256" to (if(wrongHash)"0".repeat(64) else hash).j()))),"retained" to JsonArray(listOf(obj("media_id" to image.j()))))
        return ByteArrayOutputStream().also{output->PortableCipher.encrypting(output,pass).use{cipher->ZipOutputStream(cipher).use{zip->
            for((name,data)in listOf("media/$image.bin" to bytes,"manifest.json" to manifest.toString().toByteArray())+(extra?.let{listOf(it to byteArrayOf(1))}?:emptyList())){zip.putNextEntry(ZipEntry(name));zip.write(data);zip.closeEntry()}
        }}}.toByteArray()
    }
    @Test fun zipStreamsVerifyEveryAttachmentAndFinalEncryptedFrame()=runTest {
        var received=0;val bytes=archive();val file=PortableArchive.read(ByteArrayInputStream(bytes),pass){id,data->assertEquals(image,id);received=data.size}
        assertEquals(150000,received);assertEquals(1L,file.number("version"))
        assertFails{PortableArchive.read(ByteArrayInputStream(bytes.copyOf(bytes.size-20)),pass)}
        assertFails{PortableArchive.read(ByteArrayInputStream(archive(wrongHash=true)),pass)}
        assertFails{PortableArchive.read(ByteArrayInputStream(archive(extra="../unexpected")),pass)}
    }
}
