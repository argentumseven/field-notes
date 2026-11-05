package org.fieldnotes.app

import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.fieldnotes.domain.*
import org.fieldnotes.platform.PortableArchive
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.util.Base64
import java.util.UUID

class ArchiveDeviceTest {
    @Test fun androidDocumentExportKeepsOfflineImagesKeysAndExistingFiles()=runBlocking {
        assumeTrue(Build.VERSION.SDK_INT>=29)
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val app=context.applicationContext as FieldNotesApplication;app.initialized.await();val original=app.model
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val task=UUID.randomUUID().toString();val image=UUID.randomUUID().toString();val epoch=UUID.randomUUID().toString();val user=UUID.randomUUID().toString()
        val bitmap=android.graphics.Bitmap.createBitmap(2,2,android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.setPixel(0,0,android.graphics.Color.rgb(30,90,160))
        val bytes=java.io.ByteArrayOutputStream().also{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}.toByteArray();bitmap.recycle()
        val snapshot=Snapshot(1,epoch,1,1,listOf(Record(task,"task",1,obj("title" to "An offline household task".j()))),Actor(user,"Alex","owner","PRIVATE-SESSION-ID"))
        val initial=Cache(keys=obj("private_key" to "PRIVATE-WIREGUARD".j()),config=obj("server_key" to "fixture".j(),"preshared_key" to "PRIVATE-PSK".j()),token="PRIVATE-TOKEN",snapshot=snapshot,outbox=listOf(Pending(Command(UUID.randomUUID().toString(),epoch,1,"image.create",image,null,obj("task_id" to task.j(),"name" to "offline.png".j(),"bytes" to Base64.getEncoder().encodeToString(bytes).j())))),preferences=Preferences(backgroundSync=false,liveUpdates=false,reminders=false,sharedAdditions=false))
        var saved=initial
        val vault=object:Vault{override suspend fun load()=saved;override suspend fun save(cache:Cache){saved=cache};override suspend fun clearImages(){}}
        val transport=object:Transport{override suspend fun keys()=obj();override suspend fun configure(configuration:JsonObject){};override suspend fun stop(){};override suspend fun exchange(path:String,token:String?,body:JsonObject?,serverKey:String?)=error("Offline test")}
        val fixture=Workspace(transport,vault,{UUID.randomUUID().toString()},scope);fixture.initialize();app.model=fixture
        val files=PortableFiles(app);val resolver=context.contentResolver
        fun newFile()=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply{put(MediaStore.MediaColumns.DISPLAY_NAME,"field-notes-test-${UUID.randomUUID()}.fieldnotes");put(MediaStore.MediaColumns.MIME_TYPE,"application/octet-stream");put(MediaStore.MediaColumns.IS_PENDING,1)})!!
        val uri=newFile();val occupied=newFile();val pass="A portable café 🌙 with punctuation!"
        try{
            files.export(uri,pass.toCharArray());withTimeout(180_000){while(files.state.value.busy||files.state.value.message.isBlank())delay(100)};assertTrue(files.state.value.message,files.state.value.message.startsWith("Encrypted export saved"))
            resolver.openInputStream(uri)!!.use{input->java.io.File(context.filesDir,"portable-interop.fieldnotes").outputStream().use{input.copyTo(it)}}
            val manifest=PortableArchive.read(resolver.openInputStream(uri)!!,pass.toCharArray()){id,data->assertEquals(image,id);assertArrayEquals(bytes,data)}
            assertEquals(2,manifest["records"]!!.jsonArray.size);assertEquals(1,manifest["retained"]!!.jsonArray.size)
            for(secret in listOf("PRIVATE-WIREGUARD","PRIVATE-PSK","PRIVATE-TOKEN","PRIVATE-SESSION-ID"))assertFalse(manifest.toString().contains(secret))
            assertEquals(initial,saved);assertEquals(initial,fixture.state.value.cache)
            resolver.openOutputStream(occupied,"w")!!.use{it.write("KEEP EXISTING FILE".toByteArray())}
            files.export(occupied,pass.toCharArray());withTimeout(20_000){while(!files.state.value.message.contains("new empty file"))delay(100)}
            assertEquals("KEEP EXISTING FILE",resolver.openInputStream(occupied)!!.bufferedReader().use{it.readText()})
        }finally{files.dispose();app.model=original;scope.cancel();resolver.delete(uri,null,null);resolver.delete(occupied,null,null)}
    }
}
