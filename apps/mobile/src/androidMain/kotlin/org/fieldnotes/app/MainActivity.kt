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
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.fieldnotes.domain.*
import org.fieldnotes.ui.FieldNotesApp
import org.fieldnotes.ui.FieldNotesTheme

class MainActivity : ComponentActivity() {
    private val app get() = application as FieldNotesApplication
    private var invitationCode by mutableStateOf<String?>(null)
    private var scanning by mutableStateOf(false)
    private var unlocked by mutableStateOf(false)
    private var prompting = false
    private var lastUnlock = 0L
    private val confirm = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        prompting = false
        unlocked = result.resultCode == RESULT_OK
        if (unlocked) lastUnlock = SystemClock.elapsedRealtime()
    }
    private fun unlock() {
        if (prompting) return
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isDeviceSecure) { unlocked = true; return }
        @Suppress("DEPRECATION") val intent = keyguard.createConfirmDeviceCredentialIntent("Unlock Field Notes", "Confirm your device credential to open your household workspace.")
        if (intent != null) { prompting = true; confirm.launch(intent) }
    }
    override fun onResume() {
        super.onResume()
        if (lastUnlock == 0L || SystemClock.elapsedRealtime() - lastUnlock > 5 * 60_000) { unlocked = false; unlock() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        invitationCode = intent?.data?.toString()?.takeIf { it.startsWith("fieldnotes://enroll#") && it.length <= 20000 }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        lifecycleScope.launch {
            app.initialized.await()
            CatchUpWorker.schedule(this@MainActivity, app.model.state.value.cache.preferences)
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) { if (unlocked) app.model.sync(); delay(15_000) }
            }
        }
        setContent {
            if (!unlocked) FieldNotesTheme { Surface(Modifier.fillMaxSize()) { Column(Modifier.padding(30.dp), verticalArrangement = Arrangement.Center) {
                Text("Your workspace is locked", style = MaterialTheme.typography.headlineSmall)
                Button(onClick = { unlock() }, modifier = Modifier.padding(top = 20.dp)) { Text("Unlock with device credential") }
            } } }
            else {
                val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> scanning = granted }
                if (scanning) FieldNotesTheme { EnrollmentScanner(this, { invitationCode = it; scanning = false }, { scanning = false }) }
                var back by remember { mutableStateOf<(() -> Unit)?>(null) }
                var pendingImage by rememberSaveable { mutableStateOf<String?>(null) }
                var pendingEpoch by rememberSaveable { mutableStateOf<String?>(null) }
                var pendingGeneration by rememberSaveable { mutableStateOf<Long?>(null) }
                val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    if (!granted) Toast.makeText(this, "Notifications are disabled. Catch-up can still run; enable notifications in Android Settings when you want alerts.", Toast.LENGTH_LONG).show()
                }
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                    val target = pendingImage; pendingImage = null
                    val epoch = pendingEpoch; val generation = pendingGeneration
                    if (uri != null && target != null && epoch != null && generation != null) app.model.launch { attach(target, uri, Basis(epoch, generation)) }
                }
                BackHandler(enabled = back != null) { back?.invoke() }
                val lifecycleState by lifecycle.currentStateFlow.collectAsState()
                FieldNotesApp(app.model,
                    enrollmentPolling = lifecycleState.isAtLeast(Lifecycle.State.STARTED),
                    invitationCode = invitationCode,
                    scanInvitation = { if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanning = true else cameraPermission.launch(Manifest.permission.CAMERA) },
                    copyText = { text -> getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Field Notes", text).apply { description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) } }) },
                    attachImage = { id -> pendingImage = id; pendingEpoch = app.model.state.value.cache.snapshot?.epoch; pendingGeneration = app.model.state.value.cache.snapshot?.generation; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    configureBackground = { preferences ->
                        CatchUpWorker.schedule(this, preferences)
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
