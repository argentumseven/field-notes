package org.fieldnotes.platform

import kotlinx.serialization.json.*
import org.fieldnotes.domain.*
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Bounded, fully verified archive reader shared by Android and host checks. */
object PortableArchive {
    private val uuid=Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    private fun digest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun JsonElement?.orEmptyArray()=(this as? JsonArray).orEmpty()
    suspend fun read(input:java.io.InputStream,pass:CharArray,consume:suspend(String,ByteArray)->Unit={_,_->}):JsonObject {
        val observed=mutableMapOf<String,Pair<Int,String>>();var result:JsonObject?=null;var total=0L;val names=mutableSetOf<String>()
        PortableCipher.decrypting(input,pass).use{cipher->ZipInputStream(cipher).use{zip->
            while(true){val entry=zip.nextEntry?:break;val name=entry.name
                check(names.add(name)&&names.size<=10001&&!entry.isDirectory&&(name=="manifest.json"||name.startsWith("media/")&&name.endsWith(".bin")&&uuid.matches(name.removePrefix("media/").removeSuffix(".bin")))){"Unexpected or duplicate archive file."}
                val maximum=if(name=="manifest.json")24*1024*1024 else 8*1024*1024
                check(entry.size<=maximum){"Archive entry exceeds its safe size bound."}
                val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(65536)
                while(true){val n=zip.read(buffer);if(n<0)break;total+=n;check(output.size()+n<=maximum&&total<=PortableCipher.MAX_PLAIN){"Archive expands beyond its safe size bound."};output.write(buffer,0,n)}
                val bytes=output.toByteArray()
                try{if(name=="manifest.json")result=WireJson.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence=true)).jsonObject
                    else{val id=name.removePrefix("media/").removeSuffix(".bin");observed[id]=bytes.size to digest(bytes);consume(id,bytes)}}finally{bytes.fill(0);buffer.fill(0)}
                zip.closeEntry()
            }
        }} // Closing the framed stream also verifies the mandatory final frame.
        val m=checkNotNull(result){"The archive manifest is missing."}
        check(m.text("format")=="org.fieldnotes.archive"&&m.number("version") in 1L..3L&&uuid.matches(m.text("source"))&&m.number("generation")>0){"Unsupported Field Notes archive format."}
        val records=m["records"]!!.jsonArray;check(records.size<=10000&&records.map{it.jsonObject.text("id")}.distinct().size==records.size){"Archive contains duplicate item identities."}
        val media=m["media"]!!.jsonArray.map{it.jsonObject};check(media.size==observed.size&&media.map{it.text("id")}.distinct().size==media.size){"Archive image files differ from the manifest."}
        for(item in media)check(observed[item.text("id")]?.let{it.first.toLong()==item.number("size")&&it.second==item.text("sha256")}==true){"An archive attachment is incomplete or has a bad checksum."}
        val required=records.filter{it.jsonObject.text("kind")=="image"}.map{it.jsonObject.text("id")}+m["retained"].orEmptyArray().mapNotNull{it.jsonObject["media_id"]?.jsonPrimitive?.contentOrNull}
        check(required.all{observed.containsKey(it)}){"An archive attachment is missing."}
        return m
    }
}
