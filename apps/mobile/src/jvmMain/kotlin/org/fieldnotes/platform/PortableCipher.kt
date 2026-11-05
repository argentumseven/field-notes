package org.fieldnotes.platform

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Portable file v1. A final authenticated empty frame is mandatory: neither a
 * valid prefix nor a reordered frame is a complete export. Never buffers an
 * entire attachment in an AEAD provider. Identical format to the web exporter. */
object PortableCipher {
    val magic = "FNARCHIVE1\n".toByteArray(Charsets.UTF_8)
    const val BLOCK = 65536
    const val MAX_FILE = 256L * 1024 * 1024
    const val MAX_PLAIN = 216L * 1024 * 1024
    private fun intBytes(value:Int)=ByteBuffer.allocate(4).putInt(value).array()
    private fun key(pass:CharArray,salt:ByteArray):SecretKeySpec {
        val text=String(pass).toByteArray(Charsets.UTF_8)
        try { require(text.size in 12..256) { "Use a passphrase from 12 to 256 UTF-8 bytes. Spaces and punctuation are kept exactly." } }
        finally { text.fill(0) }
        val spec=PBEKeySpec(pass,salt,600000,256)
        return try { val bytes=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try { SecretKeySpec(bytes,"AES") } finally { bytes.fill(0) }
        } finally { spec.clearPassword() }
    }
    private fun crypt(mode:Int,secret:SecretKeySpec,header:ByteArray,counter:Int,length:Int,bytes:ByteArray):ByteArray {
        val number=intBytes(counter);val nonce=header.copyOfRange(magic.size+16,header.size)+number
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(mode,secret,GCMParameterSpec(128,nonce));updateAAD(header+number+intBytes(length));doFinal(bytes)
        }
    }
    private fun InputStream.exact(size:Int):ByteArray {
        val result=ByteArray(size);var offset=0
        while(offset<size){val n=read(result,offset,size-offset);check(n>0) { "The encrypted file is incomplete." };offset+=n}
        return result
    }
    fun encrypting(output:OutputStream,pass:CharArray):OutputStream {
        val salt=ByteArray(16).also{SecureRandom().nextBytes(it)};val prefix=ByteArray(8).also{SecureRandom().nextBytes(it)}
        val header=magic+salt+prefix;val secret=key(pass,salt);output.write(header)
        return object:OutputStream(){
            val buffer=ByteArray(BLOCK);var used=0;var counter=0;var total=0L;var closed=false
            fun frame(bytes:ByteArray){val length=bytes.size+16;output.write(intBytes(length));output.write(crypt(Cipher.ENCRYPT_MODE,secret,header,counter++,length,bytes));bytes.fill(0)}
            override fun write(value:Int){write(byteArrayOf(value.toByte()))}
            override fun write(bytes:ByteArray,offset:Int,length:Int){
                check(!closed);require(offset>=0&&length>=0&&offset<=bytes.size-length);total+=length;check(total<=MAX_PLAIN){"Export exceeds the portable size limit."}
                var pos=offset;var remaining=length
                while(remaining>0){val n=minOf(BLOCK-used,remaining);bytes.copyInto(buffer,used,pos,pos+n);used+=n;pos+=n;remaining-=n;if(used==BLOCK){frame(buffer.copyOf());buffer.fill(0);used=0}}
            }
            override fun flush(){output.flush()}
            override fun close(){if(closed)return;closed=true;try{if(used>0)frame(buffer.copyOf(used));frame(ByteArray(0));output.flush()}finally{buffer.fill(0);output.close()}}
        }
    }
    fun decrypting(input:InputStream,pass:CharArray):InputStream {
        val header=input.exact(magic.size+24);require(header.copyOfRange(0,magic.size).contentEquals(magic)){"Choose an encrypted Field Notes .fieldnotes file."}
        val secret=key(pass,header.copyOfRange(magic.size,magic.size+16))
        return object:InputStream(){
            var buffer=ByteArray(0);var at=0;var counter=0;var total=0L;var consumed=header.size.toLong();var final=false;var closed=false
            fun next():Boolean {
                if(final)return false;buffer.fill(0)
                try{val lengthBytes=input.exact(4);val length=ByteBuffer.wrap(lengthBytes).int
                    check(length in 16..BLOCK+16){"Invalid encrypted frame."};consumed+=4+length;check(consumed<=MAX_FILE)
                    buffer=crypt(Cipher.DECRYPT_MODE,secret,header,counter++,length,input.exact(length));at=0
                    if(buffer.isEmpty()){check(input.read()==-1){"Extra data follows the archive."};final=true;return false}
                    total+=buffer.size;check(total<=MAX_PLAIN){"Archive exceeds the supported size."};return true
                }catch(e:Exception){buffer.fill(0);throw IllegalArgumentException("The passphrase is incorrect or this file is damaged. No planner data was imported.",e)}
            }
            override fun read():Int {check(!closed);if(at==buffer.size&&!next())return -1;return buffer[at++].toInt() and 255}
            override fun read(bytes:ByteArray,offset:Int,length:Int):Int {check(!closed);require(offset>=0&&length>=0&&offset<=bytes.size-length);if(length==0)return 0;if(at==buffer.size&&!next())return -1;val n=minOf(length,buffer.size-at);buffer.copyInto(bytes,offset,at,at+n);at+=n;return n}
            override fun close(){if(closed)return;try { val discard=ByteArray(BLOCK);while(read(discard)!=-1)discard.fill(0) } finally { closed=true;buffer.fill(0);input.close() }}
        }
    }
}
