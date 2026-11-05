package org.fieldnotes.domain

import org.fieldnotes.platform.PortableCipher
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.test.*
import kotlinx.serialization.json.*

class PortableCipherTest {
    private val password="A portable café 🌙 with punctuation!".toCharArray()
    private fun encrypt(bytes:ByteArray)=ByteArrayOutputStream().also { output->PortableCipher.encrypting(output,password).use{it.write(bytes)} }.toByteArray()
    private fun decrypt(bytes:ByteArray,pass:CharArray=password)=PortableCipher.decrypting(ByteArrayInputStream(bytes),pass).use{it.readBytes()}
    @Test fun framedRoundTripAndTermination(){
        val plain=ByteArray(180000){(it%251).toByte()};val encrypted=encrypt(plain)
        assertContentEquals(plain,decrypt(encrypted));assertFails{decrypt(encrypted,"an incorrect password!".toCharArray())}
        assertFails{decrypt(encrypted.copyOf(encrypted.size-20))};assertFails{decrypt(encrypted+byteArrayOf(0))}
        val altered=encrypted.copyOf();altered[100]=(altered[100].toInt() xor 1).toByte();assertFails{decrypt(altered)}
        val reordered=encrypted.copyOf();val size=65556;encrypted.copyInto(reordered,35,35+size,35+2*size);encrypted.copyInto(reordered,35+size,35,35+size);assertFails{decrypt(reordered)}
    }
    @Test fun webCryptoInteroperability(){
        val vector=WireJson.parseToJsonElement(javaClass.getResourceAsStream("/archive-v1.json")!!.bufferedReader().use{it.readText()}).jsonObject
        assertEquals(vector.text("plaintext_utf8"),decrypt(Base64.getDecoder().decode(vector.text("ciphertext_base64")),vector.text("passphrase").toCharArray()).decodeToString())
    }
}
