package org.fieldnotes.app

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.fieldnotes.domain.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.Assert.*

/** Only the disposable upgrade harness supplies these phases. */
class UpgradeDeviceTest {
 @Test fun apkUpgradePreservesIdentityAndPendingWork()=runBlocking {
  val args=InstrumentationRegistry.getArguments();val phase=args.getString("upgradePhase")
  assumeTrue("Use the dedicated upgrade fixture",phase in listOf("seed","verify"))
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val app=context.applicationContext as FieldNotesApplication;app.initialized.await()
  val proof=File(context.filesDir,"upgrade-proof.txt")
  // New default preferences legitimately add serialized fields. Compare the
  // retained identity, binding, commands and original delivery choices instead
  // of treating a compatible schema extension as corruption.
  fun digest(cache:Cache):String {
   val stable=obj("keys" to (cache.keys ?: JsonNull),"config" to (cache.config ?: JsonNull),
    "commands" to WireJson.encodeToJsonElement(cache.outbox.map { it.command }),
    "preferences" to obj("backgroundSync" to JsonPrimitive(cache.preferences.backgroundSync),"reminders" to JsonPrimitive(cache.preferences.reminders),"liveUpdates" to JsonPrimitive(cache.preferences.liveUpdates),"widgetTitles" to JsonPrimitive(cache.preferences.widgetTitles)))
   return MessageDigest.getInstance("SHA-256").digest(stable.toString().toByteArray()).joinToString(""){"%02x".format(it)}
  }
  if(phase=="seed"){
   val keys=app.transport.keys()
   val command=obj("operation_id" to "11111111-1111-4111-8111-111111111111".j(),"epoch" to "upgrade-epoch".j(),"generation" to 1.j(),"action" to "task.create".j(),"target" to "22222222-2222-4222-8222-222222222222".j(),"expected_revision" to JsonNull,"data" to obj("title" to "Keep this queued household edit".j()))
   val config=obj("server_key" to "saved-upgrade-server".j(),"private_key" to keys.getValue("private_key"),"preshared_key" to "synthetic per-phone PSK".j(),"ca_pem" to "synthetic pinned CA".j(),"address" to "10.82.0.2/32".j(),"endpoint" to "127.0.0.1:49272".j(),"api" to "https://10.82.0.1:8443".j())
   // Decode the legacy wire shape with the installed app's serializer. Calling
   // a newer Preferences constructor against an older APK is not ABI-safe.
   val cache=WireJson.decodeFromJsonElement<Cache>(obj("keys" to keys,"config" to config,"preferences" to obj("backgroundSync" to JsonPrimitive(false),"reminders" to JsonPrimitive(false),"liveUpdates" to JsonPrimitive(false)),"outbox" to JsonArray(listOf(obj("command" to command)))))
   app.vault.save(cache);proof.writeText(digest(cache));assertNotNull(app.vault.load().keys)
  }else{
   val loaded=app.vault.load();assertEquals(proof.readText(),digest(loaded))
   assertNotNull(loaded.keys);assertEquals("saved-upgrade-server",loaded.config!!.text("server_key"))
   assertEquals("Keep this queued household edit",loaded.outbox.single().command.data.text("title"))
  }
 }
}
