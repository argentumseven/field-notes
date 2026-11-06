package org.fieldnotes.app

import android.Manifest
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Base64
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.fieldnotes.domain.*
import org.fieldnotes.ui.FieldNotesApp
import org.fieldnotes.ui.FieldNotesTheme
import org.fieldnotes.ui.ControlShape
import org.fieldnotes.ui.DeviceActions

class MainActivity : ComponentActivity() {
    private val app get() = application as FieldNotesApplication
    private var invitationCode by mutableStateOf<String?>(null)
    private var invitationSequence by mutableIntStateOf(0)
    private var requestedRecord by mutableStateOf<String?>(null)
    private var navigationSequence by mutableIntStateOf(0)
    private var requestedDate by mutableStateOf<String?>(null)
    private var requestedPage by mutableStateOf<String?>(null)
    private var invitationScanError by mutableStateOf<String?>(null)
    private var unlocked by mutableStateOf(false)
    private var prompting = false
    private var lastUnlock by mutableStateOf(0L)
    private var deliveryWarning by mutableStateOf<String?>(null)
    private var deliveryStatus by mutableStateOf("")
    private var exportPassphrase:CharArray?=null
    private val exportDocument=registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->
        val pass=exportPassphrase;exportPassphrase=null
        if(uri!=null&&pass!=null)app.files.export(uri,pass) else {
            pass?.fill('\u0000')
            if(uri!=null){
                try{if(emptyExportDocument(contentResolver,uri))android.provider.DocumentsContract.deleteDocument(contentResolver,uri)}catch(_:Exception){}
                Toast.makeText(this,"Export canceled because the screen was recreated. Choose Export again.",Toast.LENGTH_LONG).show()
            }
        }
    }
    private val importDocument=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)app.files.choose(uri)}
    private val invitationImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) lifecycleScope.launch {
            try {
                val raw=withContext(Dispatchers.IO) { readInvitationQrImage(contentResolver,uri) }
                invitationCode=raw;invitationSequence++;invitationScanError=null
            } catch(e:Exception) {
                if(e is CancellationException)throw e
                invitationScanError=e.message ?: "This QR image could not be read."
            }
        }
    }
    private val invitationScanner = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { raw ->
            if (raw.length <= 20000) {
                invitationCode = raw
                invitationSequence++
                invitationScanError = null
            } else invitationScanError = "This QR is too large to be a Field Notes invitation."
        }
    }
    private fun scanInvitation() {
        invitationScanError = null
        try {
            invitationScanner.launch(ScanOptions()
                .setCaptureActivity(EnrollmentCaptureActivity::class.java)
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Scan the invitation shown by your Field Notes server")
                .setBeepEnabled(false)
                .setBarcodeImageEnabled(false)
                .setOrientationLocked(false))
        } catch (_: Exception) {
            invitationScanError = "Camera unavailable. You can paste the invitation instead."
        }
    }
    override fun onStart(){super.onStart();app.uiVisible=true}
    override fun onStop(){app.uiVisible=false;super.onStop()}
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        navigationSequence++
        requestedRecord = intent.getStringExtra("open_record")
        requestedDate = intent.getStringExtra("open_date")
        requestedPage = intent.getStringExtra("open_page")
        intent.data?.toString()?.takeIf { it.startsWith("fieldnotes://enroll#") && it.length <= 20000 }?.let {
            invitationCode = it
            invitationSequence++
            invitationScanError = null
        }
    }
    private val confirm = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        prompting = false
        unlocked = result.resultCode == RESULT_OK
        if (unlocked) lastUnlock = SystemClock.elapsedRealtime()
    }
    private fun unlock() {
        if (prompting) return
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isDeviceSecure) { lastUnlock=SystemClock.elapsedRealtime();unlocked = true; return }
        @Suppress("DEPRECATION") val intent = keyguard.createConfirmDeviceCredentialIntent("Unlock Field Notes", "Confirm your device credential to open your household workspace.")
        if (intent != null) { prompting = true; confirm.launch(intent) }
    }
    override fun onResume() {
        super.onResume()
        app.updates.installerReturned()
        lifecycleScope.launch { val status=withContext(Dispatchers.IO){DeliverySettings.summary(this@MainActivity) to DeliverySettings.problem(this@MainActivity)};deliveryStatus=status.first;deliveryWarning=status.second }
        app.scope.launch { try{LocalAlarms.reconcile(this@MainActivity,true)}catch(_:Exception){app.deliveryError.value="Reminder scheduling needs attention."} }
        lifecycleScope.launch {
            app.initialized.await()
            val prefs=app.model.state.value.cache.preferences
            if(!prefs.appLock)unlocked=true
            else if(lastUnlock==0L||SystemClock.elapsedRealtime()-lastUnlock>prefs.lockMinutes.coerceIn(1,30)*60_000L){unlocked=false;unlock()}
        }
    }
    override fun onDestroy(){exportPassphrase?.fill('\u0000');exportPassphrase=null;super.onDestroy()}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestedRecord = intent?.getStringExtra("open_record")
        requestedDate = intent?.getStringExtra("open_date")
        requestedPage = intent?.getStringExtra("open_page")
        invitationCode = intent?.data?.toString()?.takeIf { it.startsWith("fieldnotes://enroll#") && it.length <= 20000 }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        lifecycleScope.launch {
            app.initialized.await()
            try { withContext(Dispatchers.IO){CatchUpWorker.schedule(this@MainActivity, app.model.state.value.cache.preferences)} }
            catch(e:kotlinx.coroutines.CancellationException){throw e}
            catch(_:Exception){app.deliveryError.value="Background scheduling could not start. Open Settings to review delivery; cached work is kept."}
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                app.model.followForegroundUpdates()
            }
        }
        setContent {
            val workspace by app.model.state.collectAsState()
            LaunchedEffect(workspace.ready,workspace.cache.preferences.appLock) {
                if(workspace.ready&&workspace.cache.preferences.appLock&&lastUnlock==0L){unlocked=false;unlock()}
                else if(!workspace.cache.preferences.appLock)unlocked=true
            }
            if (workspace.ready&&workspace.cache.preferences.appLock&&(!unlocked||lastUnlock==0L)) FieldNotesTheme { Surface(Modifier.fillMaxSize()) { Column(Modifier.padding(30.dp), verticalArrangement = Arrangement.Center) {
                Text("Your workspace is locked", style = MaterialTheme.typography.headlineSmall)
                Button(shape=ControlShape,onClick = { unlock() }, modifier = Modifier.padding(top = 20.dp)) { Text("Unlock with device credential") }
            } } }
            else {
                val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    if (granted) scanInvitation() else invitationScanError = "Camera permission was denied. Allow it in Android app settings, or paste the invitation."
                }
                var back by remember { mutableStateOf<(() -> Unit)?>(null) }
                var pendingImage by rememberSaveable { mutableStateOf<String?>(null) }
                var pendingEpoch by rememberSaveable { mutableStateOf<String?>(null) }
                var pendingGeneration by rememberSaveable { mutableStateOf<Long?>(null) }
                var notificationAsked by rememberSaveable { mutableStateOf(false) }
                val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    if(granted&&app.model.state.value.cache.preferences.liveUpdates)PrivateUpdatesService.start(this)
                    deliveryStatus=DeliverySettings.summary(this)
                    if (!granted) Toast.makeText(this, "Notifications are disabled. Catch-up can still run; enable notifications in Android Settings when you want alerts.", Toast.LENGTH_LONG).show()
                }
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                    val target = pendingImage; pendingImage = null
                    val epoch = pendingEpoch; val generation = pendingGeneration
                    if (uri != null && target != null && epoch != null && generation != null) app.model.launch { attach(target, uri, Basis(epoch, generation)) }
                }
                BackHandler(enabled = back != null) { back?.invoke() }
                val lifecycleState by lifecycle.currentStateFlow.collectAsState()
                val updateState by app.updates.state.collectAsState()
                val transferState by app.files.state.collectAsState()
                val deliveryIssue by app.deliveryError.collectAsState()
                val deliveryStorageIssue by app.deliveryPersistenceError.collectAsState()
                LaunchedEffect(workspace.cache.token,workspace.cache.config,workspace.connectionPaused,workspace.needsLogin,lifecycleState){
                    if(lifecycleState.isAtLeast(Lifecycle.State.RESUMED)&&workspace.ready&&!workspace.needsLogin&&!workspace.connectionPaused&&workspace.cache.token!=null){
                        while(true){app.updates.checkForUpdate(automatic=true);kotlinx.coroutines.delay(15*60*1000L)}
                    }
                }
                LaunchedEffect(workspace.cache.token,workspace.needsLogin,workspace.cache.preferences.liveUpdates,workspace.connectionPaused,lifecycleState){
                    if(workspace.connectionPaused)PrivateUpdatesService.stop(this@MainActivity)
                    else if(lifecycleState.isAtLeast(Lifecycle.State.STARTED)&&workspace.cache.token!=null&&!workspace.needsLogin&&workspace.cache.preferences.liveUpdates){
                        if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(this@MainActivity,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){if(!notificationAsked){notificationAsked=true;requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)}}
                        else {
                            // Let the initial composition finish before starting
                            // Android's foreground-service deadline.
                            withFrameNanos { };yield()
                            PrivateUpdatesService.start(this@MainActivity)
                        }
                    }
                }
                FieldNotesApp(app.model,
                    requestedRecord=requestedRecord,requestedPage=requestedPage,requestedDate=requestedDate,navigationSequence=navigationSequence,
                    deviceActions=DeviceActions(transfer=transferState,exportData={password->exportPassphrase?.fill('\u0000');exportPassphrase=password.toCharArray();exportDocument.launch("field-notes-${java.time.LocalDate.now()}.fieldnotes")},chooseImport={importDocument.launch(arrayOf("application/octet-stream","application/zip","*/*"))},previewImport=app.files::preview,reviewImport=app.files::review,commitImport=app.files::commit,cancelImport=app.files::cancel,mapImportPerson=app.files::mapPerson,refreshRecovered=app.files::refreshRecovered,restoreRecovered=app.files::restore,discardRecovered=app.files::discard,previewSound={profile->app.scope.launch { try { withContext(Dispatchers.IO){ReminderSounds.preview(this@MainActivity,profile)} }catch(_:Exception){Toast.makeText(this@MainActivity,"Sound preview unavailable. Check Android notification settings.",Toast.LENGTH_LONG).show()} }},openLink={url->
                        val uri=Uri.parse(url)
                        if(uri.scheme in setOf("http","https")&&!uri.host.isNullOrBlank())try{startActivity(Intent(Intent.ACTION_VIEW,uri).addCategory(Intent.CATEGORY_BROWSABLE))}catch(_:Exception){Toast.makeText(this,"No browser is available to open this link.",Toast.LENGTH_LONG).show()}
                    },androidDelivery=true,status=deliveryStatus,warning=listOfNotNull(deliveryWarning,deliveryIssue,deliveryStorageIssue).distinct().joinToString("\n"),use24Hour=android.text.format.DateFormat.is24HourFormat(this),addCalendarWidget={if(!CalendarWidget.pin(this))Toast.makeText(this,"Add Field Notes · Calendar from your launcher’s widget picker.",Toast.LENGTH_LONG).show()},
                        addWeekWidget={if(!WeekCalendarWidget.pin(this))Toast.makeText(this,"Add Field Notes · Week from your launcher's widget picker.",Toast.LENGTH_LONG).show()},
                        testLoudAlarm={AlarmPlaybackService.test(this)},stopAlarm={app.scope.launch{try{AlarmPlaybackService.stop(this@MainActivity);AlarmAudio.restoreAfterStop(this@MainActivity)}catch(e:Exception){if(e is CancellationException)throw e;app.deliveryError.value=e.message ?: "Alarm volume could not be restored. Open Android sound settings."}}},soundSettings={DeliverySettings.open(this,"sound")},dndSettings={DeliverySettings.open(this,"dnd")},recheckDelivery={app.scope.launch{DeliverySettings.recheck(this@MainActivity);deliveryWarning=DeliverySettings.problem(this@MainActivity);deliveryStatus=DeliverySettings.summary(this@MainActivity)}},
                        update=updateState,checkUpdate={app.updates.checkForUpdate()},downloadUpdate={app.updates.download()},cancelUpdate={app.updates.cancel()},installUpdate={app.updates.install(this)},
                        addUpcomingWidget={DeliverySettings.pin(this)},profileTest={val ok=Alerts.scheduleProfileTest(this);Toast.makeText(this,if(ok)"Test scheduled for 30 seconds. Switch profiles now." else "Allow notifications in Android settings first.",Toast.LENGTH_LONG).show()},testAlert={if(!Alerts.test(this))Toast.makeText(this,"Allow notifications in Android settings first.",Toast.LENGTH_LONG).show()},
                        notificationSettings={DeliverySettings.open(this,"notifications")},alarmSettings={DeliverySettings.open(this,"alarms")},
                        batterySettings={DeliverySettings.open(this,"battery")},profileSettings={DeliverySettings.open(this,"profiles")},appSettings={DeliverySettings.open(this,"app")},
                        removeInstallation={DeliverySettings.remove(this@MainActivity)}),
                    enrollmentPolling = lifecycleState.isAtLeast(Lifecycle.State.STARTED),
                    invitationCode = invitationCode,
                    invitationScanError = invitationScanError,
                    invitationSequence = invitationSequence,
                    chooseInvitationImage = { invitationImage.launch(arrayOf("image/*")) }, scanInvitation = { if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanInvitation() else cameraPermission.launch(Manifest.permission.CAMERA) },
                    copyText = { text -> getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Field Notes", text).apply { description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) } }) },
                    attachImage = { id -> pendingImage = id; pendingEpoch = app.model.state.value.cache.snapshot?.epoch; pendingGeneration = app.model.state.value.cache.snapshot?.generation; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    configureBackground = { preferences ->
                        app.scope.launch(Dispatchers.IO) {
                            try{CatchUpWorker.schedule(this@MainActivity, preferences)}
                            catch(e:kotlinx.coroutines.CancellationException){throw e}
                            catch(_:Exception){app.deliveryError.value="Background scheduling could not be updated. Review delivery settings; cached work is kept."}
                        }
                        if(preferences.liveUpdates)PrivateUpdatesService.start(this)else PrivateUpdatesService.stop(this)
                        app.scope.launch { LocalAlarms.reconcile(this@MainActivity,true) }
                        if ((preferences.backgroundSync || preferences.reminders) && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    registerBack = { back = it },
                    imageContent = { row -> AttachmentImage(app, row) },
                )
            }
        }
    }
    private suspend fun attach(task: String, uri: Uri, basis: Basis) {
        val (name, bytes) = withContext(Dispatchers.IO) {
            val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: "image"
            val bytes = contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= 4*1024*1024) { "Choose an image smaller than 4 MiB." }; out.write(buffer, 0, n) }
                out.toByteArray()
            } ?: error("Image could not be opened.")
            var label = name.take(180)
            while (label.toByteArray().size > 180) label = label.dropLast(1)
            if (label.lastOrNull()?.isHighSurrogate() == true) label = label.dropLast(1)
            (label.ifBlank { "image.png" }) to bytes
        }
        app.model.enqueue("image.create", app.model.newId(), obj("task_id" to task.j(), "name" to name.j(), "bytes" to Base64.encodeToString(bytes, Base64.NO_WRAP).j()), null, basis)
        app.model.sync()
    }
}

@Composable private fun AttachmentImage(app: FieldNotesApplication, row: VisibleRecord) {
    val state by app.model.state.collectAsState()
    var version by remember { mutableIntStateOf(0) }
    val result by produceState<Pair<ImageBitmap?, String?>>(null to null, row.record.id, version, row.pending) {
        value = try {
            val binding = state.cache
            val peerKey = binding.config?.text("server_key") ?: error("Pair first.")
            val user = binding.snapshot?.actor?.user_id ?: error("Sign in first.")
            val imageScope = MessageDigest.getInstance("SHA-256").digest("$peerKey:$user".toByteArray()).joinToString("") { "%02x".format(it) }
            val bitmap = withContext(Dispatchers.IO) {
                val bytes = if (row.pending) {
                    val pending = state.cache.outbox.firstOrNull { it.command.target == row.record.id } ?: error("Pending image unavailable.")
                    Base64.decode(pending.command.data.text("bytes"), Base64.NO_WRAP)
                } else app.vault.readImage(imageScope, row.record.id) ?: app.transport.image(row.record.id, binding.token ?: error("Sign in first."), peerKey).also { app.vault.saveImage(imageScope, row.record.id, it) }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096) { "Image exceeds display limits." }
                var sample = 1; while (bounds.outWidth/sample > 1200 || bounds.outHeight/sample > 1200) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap() ?: error("Invalid image.")
            }
            bitmap to null
        } catch (e: Exception) { if (e is CancellationException) throw e; null to "Image unavailable while offline or no longer authorized." }
    }
    Column(Modifier.fillMaxWidth()) {
        result.first?.let { Image(it, row.record.data.text("name"), Modifier.fillMaxWidth().heightIn(max = 240.dp)) }
        Text(row.record.data.text("name") + if (row.pending) " · upload pending" else "", style = MaterialTheme.typography.bodySmall)
        if (result.second != null) { Text(result.second!!); TextButton(onClick = { version++ }) { Text("Retry image") } }
        val owner = state.cache.snapshot?.actor
        if (!row.pending && owner != null && (owner.role in setOf("owner", "admin") || row.record.data.text("author_id") == owner.user_id)) TextButton(onClick = { app.model.enqueueAsync("image.delete", row.record.id, obj(), row.record.revision) }) { Text("Remove image") }
    }
}
