@file:OptIn(ExperimentalMaterial3Api::class)

package fr.bonobo.coffremaison

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import fr.bonobo.coffremaison.security.AppLockManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private val Navy = Color(0xFF08131B)
private val Cyan = Color(0xFF24D7E8)
private val Orange = Color(0xFFFFA726)
private val CyanogenGreen = Color(0xFFB7E34A)
private val CyanogenOrange = Color(0xFFFFB74D)

private sealed interface TimelineEntry {
 val time: Long
}
private data class TimelineNote(val note: Note) : TimelineEntry {
 override val time get() = note.entity.updatedAt
}
private data class TimelineAttachment(val attachment: Attachment) : TimelineEntry {
 override val time get() = attachment.entity.addedAt
}

class MainActivity : FragmentActivity() {
 private val vm by viewModels<MainViewModel>()
 private val unlocked = mutableStateOf(false)
 private val theme = mutableStateOf("system")
 private var openingAttachment = false
 private val lockHandler = Handler(Looper.getMainLooper())
 private val autoLock = Runnable { if (!openingAttachment) unlocked.value = false }

 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  enableEdgeToEdge()
  window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
  cacheDir.listFiles()?.filter { it.name.startsWith("share_") || it.name.startsWith("pdf_preview_") }.orEmpty().forEach { it.delete() }
  theme.value = getSharedPreferences("coffre_settings", MODE_PRIVATE).getString("theme", "system") ?: "system"
  val lock = AppLockManager(this)

  setContent {
   AppTheme(theme.value) {
    if (unlocked.value) {
     Home(vm, theme.value, { openingAttachment = true }) { m ->
      theme.value = m
      getSharedPreferences("coffre_settings", MODE_PRIVATE).edit().putString("theme", m).apply()
     }
    } else {
     Lock(this, lock) { unlocked.value = true; scheduleAutoLock() }
    }
   }
  }
 }

 override fun onStop() {
  super.onStop()
  lockHandler.removeCallbacks(autoLock)
  if (!isChangingConfigurations && !openingAttachment && unlocked.value) lockHandler.postDelayed(autoLock, 5 * 60 * 1000L)
  openingAttachment = false
 }
 override fun onResume() {
  super.onResume()
  if (unlocked.value) scheduleAutoLock()
 }
 override fun onUserInteraction() {
  super.onUserInteraction()
  if (unlocked.value) scheduleAutoLock()
 }
 private fun scheduleAutoLock() { lockHandler.removeCallbacks(autoLock); lockHandler.postDelayed(autoLock, 5 * 60 * 1000L) }
}

@Composable
private fun AppTheme(mode: String, content: @Composable () -> Unit) {
 val dark = when (mode) {
  "light" -> false
  "dark", "cyanogen" -> true
  else -> isSystemInDarkTheme()
 }
 val c = if (dark) {
  val primary = if (mode == "cyanogen") CyanogenGreen else Cyan
  darkColorScheme(
   primary = primary,
   secondary = if (mode == "cyanogen") CyanogenOrange else Cyan,
   tertiary = if (mode == "cyanogen") Color(0xFF8BC34A) else Cyan,
   onPrimary = Navy,
   background = Navy,
   onBackground = if (mode == "cyanogen") CyanogenGreen else Cyan,
   surface = Color(0xE8121A30),
   onSurface = Color.White,
   surfaceVariant = Color(0xFF1B2D3A),
   onSurfaceVariant = Color(0xFFAAB3D8)
  )
 } else {
  lightColorScheme(
   primary = Color.Black,
   onPrimary = Color.White,
   background = Color(0xFFF6FBFC),
   onBackground = Color.Black,
   surface = Color.White,
   onSurface = Color.Black,
   surfaceVariant = Color(0xFFE9F0F2),
   onSurfaceVariant = Color(0xFF303238)
  )
 }
 MaterialTheme(c, content = content)
}

@Composable
private fun Lock(a: FragmentActivity, lock: AppLockManager, done: () -> Unit) {
 var pin by remember { mutableStateOf("") }
 var conf by remember { mutableStateOf("") }
 val setup = !lock.hasPin()
 var error by remember { mutableStateOf("") }
 val field = OutlinedTextFieldDefaults.colors(
  focusedTextColor = Color.White,
  unfocusedTextColor = Color.White,
  focusedLabelColor = Cyan,
  unfocusedLabelColor = Color(0xFFAAB3D8),
  focusedBorderColor = Cyan,
  unfocusedBorderColor = Color(0xFFAAB3D8),
  cursorColor = Cyan
 )

 Box(Modifier.fillMaxSize().background(Navy), contentAlignment = Alignment.Center) {
  Image(
   painterResource(R.drawable.coffre_maison_background),
   null,
   Modifier.fillMaxSize(),
   contentScale = ContentScale.Crop,
   alpha = .82f
  )
  Box(Modifier.fillMaxSize().background(Navy.copy(.35f)))
  Card(
   Modifier.padding(28.dp),
   colors = CardDefaults.cardColors(containerColor = Color(0xB8121A30)),
   border = BorderStroke(1.dp, Cyan.copy(.5f)),
   shape = RoundedCornerShape(26.dp)
  ) {
   Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    Icon(Icons.Default.Shield, null, tint = Cyan, modifier = Modifier.size(52.dp))
    Text("Coffre Maison", style = MaterialTheme.typography.headlineSmall, color = Cyan)
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
     pin,
     { if (it.length <= 6 && it.all(Char::isDigit)) pin = it },
     label = { Text("Code PIN à 6 chiffres") },
     visualTransformation = PasswordVisualTransformation(),
     keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
     colors = field
    )
    if (setup) {
     Spacer(Modifier.height(8.dp))
     OutlinedTextField(
      conf,
      { if (it.length <= 6 && it.all(Char::isDigit)) conf = it },
      label = { Text("Confirmer le code") },
      visualTransformation = PasswordVisualTransformation(),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
      colors = field
     )
    }
    if (error.isNotBlank()) {
     Spacer(Modifier.height(8.dp))
     Text(error, color = Color.Red)
    }
    Spacer(Modifier.height(16.dp))
    Button(
     {
      if (setup && pin.length == 6 && pin == conf) {
       lock.setPin(pin.toCharArray())
       done()
      } else if (!setup && lock.verify(pin.toCharArray())) {
       done()
      } else {
       error = "Code incorrect ou non confirmé"
      }
     },
     colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Navy)
    ) {
     Text(if (setup) "Créer le coffre" else "Déverrouiller")
    }
    if (!setup && BiometricManager.from(a).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS) {
     Spacer(Modifier.height(8.dp))
     OutlinedButton(
      { biometric(a, done) },
      border = BorderStroke(1.dp, Cyan),
      colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
     ) {
      Icon(Icons.Default.Fingerprint, null)
      Spacer(Modifier.width(8.dp))
      Text("Empreinte")
     }
    }
   }
  }
 }
}

private fun biometric(a: FragmentActivity, ok: () -> Unit) {
 BiometricPrompt(
  a,
  ContextCompat.getMainExecutor(a),
  object : BiometricPrompt.AuthenticationCallback() {
   override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) = ok()
  }
 ).authenticate(
  BiometricPrompt.PromptInfo.Builder()
   .setTitle("Déverrouiller Coffre Maison")
   .setNegativeButtonText("Code PIN")
   .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
   .build()
 )
}

@Composable
private fun Home(vm: MainViewModel, theme: String, opening: () -> Unit, setTheme: (String) -> Unit) {
 var tab by rememberSaveable { mutableIntStateOf(0) }
 var cur by remember { mutableStateOf<Project?>(null) }
 var portableBackupUri by remember { mutableStateOf<Uri?>(null) }
 var portableRestoreUri by remember { mutableStateOf<Uri?>(null) }
 val context = LocalContext.current
  val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> portableBackupUri = uri }
  val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> portableRestoreUri = uri }
  LaunchedEffect(Unit) { vm.errors.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() } }

 if (cur != null) {
  TimelineProjectScreen(cur!!, vm, opening) { cur = null }
  return
 }

 Scaffold(
  containerColor = MaterialTheme.colorScheme.background,
  bottomBar = {
   NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
    listOf("Projets" to Icons.Default.Folder, "Réglages" to Icons.Default.Settings).forEachIndexed { i, (t, ic) ->
     NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(ic, null) }, label = { Text(t) })
    }
   }
  }
 ) { p ->
  when (tab) {
   0 -> Projects(vm, Modifier.padding(p), theme) { cur = it }
   else -> Settings(theme, setTheme, Modifier.padding(p), { opening(); export.launch("CoffreMaison-${System.currentTimeMillis()}.cmbackup") }, { opening(); restore.launch(arrayOf("application/octet-stream", "application/zip", "*/*")) })
  }
 }
 portableBackupUri?.let { uri ->
  PortablePasswordDialog("Protéger la sauvegarde", true, { portableBackupUri = null }) { password ->
   vm.exportPortableBackup(uri,password); portableBackupUri = null
  }
 }
 portableRestoreUri?.let { uri ->
  PortablePasswordDialog("Restaurer la sauvegarde", false, { portableRestoreUri = null }) { password ->
   vm.restorePortableBackup(uri,password); portableRestoreUri = null
  }
 }
}

@Composable
private fun Title() = Row(verticalAlignment = Alignment.CenterVertically) {
 Icon(Icons.Default.Shield, null, tint = MaterialTheme.colorScheme.primary)
 Spacer(Modifier.width(10.dp))
 Column {
  Text("Coffre Maison", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
  Text("Vos projets, chiffrés et privés", color = MaterialTheme.colorScheme.onSurfaceVariant)
 }
}

@Composable
private fun Banner() {
 Card(
  Modifier.fillMaxWidth().height(118.dp),
  shape = RoundedCornerShape(20.dp),
  border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(.45f))
 ) {
  Image(painterResource(R.drawable.coffre_maison_banner), "Maison et plans", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
 }
}

@Composable
private fun Projects(vm: MainViewModel, mod: Modifier, mode: String, open: (Project) -> Unit) {
 val ps by vm.projects.collectAsState()
 var add by remember { mutableStateOf(false) }
 var edit by remember { mutableStateOf<Project?>(null) }
 var remove by remember { mutableStateOf<Project?>(null) }
 val orange = mode == "dark" || mode == "cyanogen"

 Column(mod.padding(18.dp)) {
  Title()
  Spacer(Modifier.height(14.dp))
  Banner()
  Spacer(Modifier.height(20.dp))
  Row(verticalAlignment = Alignment.CenterVertically) {
   Text("Mes projets", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
   FilledTonalButton({ add = true }) {
    Icon(Icons.Default.Add, null)
    Spacer(Modifier.width(4.dp))
    Text("Projet")
   }
  }
  if (ps.isEmpty()) {
   Text("Votre coffre est prêt. Créez votre premier projet.", Modifier.padding(top = 30.dp))
  } else {
   LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 80.dp)) {
    items(ps, key = { it.entity.id }) { p ->
     Card(
      Modifier.fillMaxWidth().clickable { open(p) },
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(.45f)),
      shape = RoundedCornerShape(20.dp)
     ) {
      Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
       Icon(Icons.Default.Home, null, tint = if (orange) Orange else MaterialTheme.colorScheme.primary)
       Spacer(Modifier.width(12.dp))
       Column(Modifier.weight(1f)) {
        Text(p.title, fontWeight = FontWeight.Bold, color = if (orange) Orange else MaterialTheme.colorScheme.primary)
        Text(p.entity.category, color = MaterialTheme.colorScheme.onSurfaceVariant)
       }
       IconButton({ edit = p }) { Icon(Icons.Default.Edit, "Modifier") }
       IconButton({ remove = p }) { Icon(Icons.Default.Delete, "Supprimer") }
      }
     }
    }
   }
  }
 }

 if (add) {
  ProjectDialog({ add = false }) { t, c ->
   vm.addProject(t, c)
   add = false
  }
 }
 edit?.let { p ->
  ProjectEditDialog(p, { edit = null }) { t, c ->
   vm.updateProject(p, t, c)
   edit = null
  }
 }
 remove?.let { p ->
  ConfirmDialog("Supprimer ${p.title} ?", "Les notes et pièces jointes du projet seront supprimées.", {
   vm.delete(p.entity)
   remove = null
  }, { remove = null })
 }
}

@Composable
private fun Settings(t: String, set: (String) -> Unit, m: Modifier, backup: () -> Unit, restore: () -> Unit) = Column(
 m.padding(horizontal = 22.dp).verticalScroll(rememberScrollState()).padding(bottom = 110.dp)
) {
 Title()
 Spacer(Modifier.height(14.dp))
 Banner()
 Spacer(Modifier.height(26.dp))
 Text("Réglages", style = MaterialTheme.typography.headlineSmall)
 Text("Thème", Modifier.padding(top = 18.dp), fontWeight = FontWeight.Bold)
 listOf("system" to "Système", "light" to "Clair", "dark" to "Sombre", "cyanogen" to "CyanogenMod").forEach { (id, l) ->
  Row(Modifier.fillMaxWidth().clickable { set(id) }, verticalAlignment = Alignment.CenterVertically) {
   RadioButton(t == id, { set(id) })
   Text(l)
  }
 }
 Spacer(Modifier.height(18.dp))
 Text("Données", fontWeight = FontWeight.Bold)
 Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
  OutlinedButton(onClick = backup) { Icon(Icons.Default.Backup, null); Spacer(Modifier.width(6.dp)); Text("Sauvegarder") }
  OutlinedButton(onClick = restore) { Icon(Icons.Default.Restore, null); Spacer(Modifier.width(6.dp)); Text("Restaurer") }
 }
 Spacer(Modifier.height(22.dp))
 Text("Coffre Maison • 1.0.1\nCréé par souffly007 (Franck R.-F.)\nGNU GPL v3", color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PortablePasswordDialog(title:String, confirmation:Boolean, close:()->Unit, submit:(String)->Unit) {
 var password by remember { mutableStateOf("") }
 var confirm by remember { mutableStateOf("") }
 val valid = password.length >= 8 && (!confirmation || password == confirm)
 AlertDialog(onDismissRequest=close,title={Text(title)},text={
  Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
   Text(if(confirmation) "Ce mot de passe sera nécessaire pour restaurer la sauvegarde sur un autre appareil." else "Saisis le mot de passe utilisé lors de la sauvegarde.", color=MaterialTheme.colorScheme.onSurfaceVariant)
   OutlinedTextField(password,{password=it},label={Text("Mot de passe de sauvegarde")},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Password))
   if(confirmation) OutlinedTextField(confirm,{confirm=it},label={Text("Confirmation")},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Password))
   if(password.isNotEmpty() && password.length < 8) Text("8 caractères minimum",color=MaterialTheme.colorScheme.error)
   if(confirmation && confirm.isNotEmpty() && password != confirm) Text("Les mots de passe sont différents",color=MaterialTheme.colorScheme.error)
  }
 },confirmButton={TextButton(onClick={submit(password)},enabled=valid){Text(if(confirmation) "Sauvegarder" else "Restaurer")}},dismissButton={TextButton(close){Text("Annuler")}})
}

@Composable
private fun TimelineProjectScreen(p: Project, vm: MainViewModel, opening: () -> Unit, back: () -> Unit) {
 val notes by vm.notes(p.entity.id).collectAsState(initial = emptyList())
 val files by vm.attachments(p.entity.id).collectAsState(initial = emptyList())
 var add by remember { mutableStateOf(false) }
 var edit by remember { mutableStateOf<Note?>(null) }
 var remove by remember { mutableStateOf<Note?>(null) }
 var selectedImage by remember { mutableStateOf<Attachment?>(null) }
 var selectedPdf by remember { mutableStateOf<Attachment?>(null) }
 var editAttachment by remember { mutableStateOf<Attachment?>(null) }
 val context = LocalContext.current
 var cameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
 val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
  if (ok) cameraUri?.let { vm.addAttachment(p.entity.id, it) }
 }
 val picker =
  rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
   uris.forEach { vm.addAttachment(p.entity.id, it) }
  }
 val exportProject = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> uri?.let { vm.exportProject(p.entity.id, it) } }
 val entries =
  (notes.map { TimelineNote(it) } + files.map { TimelineAttachment(it) }).sortedBy { it.time }

 Scaffold(
  topBar = {
   Column {
    TopAppBar(
     title = { Text(p.title) },
     navigationIcon = { IconButton(back) { Icon(Icons.Default.ArrowBack, "Retour") } }
    )
    Row(
     Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
     horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
     FilledTonalIconButton({ add = true }) { Icon(Icons.Default.NoteAdd, "Ajouter une note") }
     FilledTonalIconButton({
      val f = File(context.cacheDir, "camera_${UUID.randomUUID()}.jpg")
      cameraUri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
      opening()
      camera.launch(cameraUri!!)
     }) { Icon(Icons.Default.CameraAlt, "Prendre une photo") }
     FilledTonalIconButton({
      opening()
      picker.launch(arrayOf("image/*", "application/pdf"))
     }) { Icon(Icons.Default.AttachFile, "Ajouter un PDF ou une photo") }
     FilledTonalIconButton({ opening(); exportProject.launch("${p.title.replace(Regex("[^A-Za-z0-9._-]"), "_")}.cmproject") }) { Icon(Icons.Default.Share, "Exporter le projet") }
    }
   }
  },
  contentWindowInsets = WindowInsets(0, 0, 0, 0)
 ) { pad ->
  LazyColumn(
   Modifier.padding(pad).padding(16.dp),
   verticalArrangement = Arrangement.spacedBy(10.dp),
   contentPadding = PaddingValues(bottom = 30.dp)
  ) {
   if (entries.isEmpty()) {
    item {
     Text(
      "Ajoute une première note, photo ou facture à ce projet.",
      color = MaterialTheme.colorScheme.onSurfaceVariant
     )
    }
   }
   items(entries) { entry ->
    when (entry) {
     is TimelineNote -> Card {
      Column(Modifier.padding(14.dp)) {
       Row(verticalAlignment = Alignment.CenterVertically) {
        Text(entry.note.title, Modifier.weight(1f), fontWeight = FontWeight.Bold)
        IconButton({ edit = entry.note }) { Icon(Icons.Default.Edit, "Modifier") }
        IconButton({ remove = entry.note }) { Icon(Icons.Default.Delete, "Supprimer") }
       }
       Text(entry.note.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
       Text(
        SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.FRENCH).format(Date(entry.time)),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp)
       )
      }
     }

     is TimelineAttachment -> {
      var thumbnail by remember(entry.attachment.entity.id) {
       mutableStateOf<ImageBitmap?>(null)
      }

      LaunchedEffect(entry.attachment.entity.id) {
       thumbnail = vm.thumbnail(entry.attachment)
      }

      Card(
       Modifier
        .fillMaxWidth()
        .clickable {
         if (entry.attachment.mimeType.startsWith("image/")) {
          selectedImage = entry.attachment
         } else {
          selectedPdf = entry.attachment
         }
        }
      ) {
       Row(
        Modifier.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
       ) {
        if (thumbnail != null) {
         Image(
          bitmap = thumbnail!!,
          contentDescription = "Aperçu photo",
          modifier = Modifier
           .size(44.dp)
           .clip(RoundedCornerShape(8.dp)),
          contentScale = ContentScale.Crop
         )
        } else {
         Icon(
          if (entry.attachment.mimeType.startsWith("image/")) {
           Icons.Default.Photo
          } else {
           Icons.Default.PictureAsPdf
          },
          null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(38.dp)
         )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
         Text(entry.attachment.title?.takeIf { it.isNotBlank() } ?: entry.attachment.displayName, fontWeight = FontWeight.Bold)
         Text(
          if (entry.attachment.mimeType.startsWith("image/")) "Photo" else "Document PDF",
          color = MaterialTheme.colorScheme.onSurfaceVariant
         )
         entry.attachment.contractor?.takeIf { it.isNotBlank() }?.let { Text("Artisan : $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
         entry.attachment.amountCents?.let { Text("Montant : %.2f €".format(Locale.FRENCH, it / 100.0), color = MaterialTheme.colorScheme.onSurfaceVariant) }
         entry.attachment.documentDate?.let { Text("Date : ${SimpleDateFormat("dd/MM/yyyy", Locale.FRENCH).format(Date(it))}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
         Text(
          SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.FRENCH).format(Date(entry.time)),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary
         )
        }
        IconButton({ editAttachment = entry.attachment }) { Icon(Icons.Default.Edit, "Modifier les informations") }
        IconButton({ vm.deleteAttachment(entry.attachment) }) {
         Icon(
          Icons.Default.Delete,
          "Supprimer"
         )
        }
       }
      }
     }
    }
   }
  }

  if (add) {
   NoteDialog({ t, b ->
    vm.addNote(p.entity.id, t, b)
    add = false
   }) { add = false }
  }
  edit?.let { n ->
   NoteEditDialog(n, { edit = null }) { t, b ->
    vm.updateNote(n, t, b)
    edit = null
   }
  }
  remove?.let { n ->
   ConfirmDialog("Supprimer la note ?", "Cette action est définitive.", {
    vm.deleteNote(n)
    remove = null
   }, { remove = null })
  }
  selectedImage?.let { image ->
   PhotoPreview(image, vm) { selectedImage = null }
  }
  selectedPdf?.let { pdf ->
   PdfReaderDialog(pdf, vm) { selectedPdf = null }
  }
  editAttachment?.let { attachment ->
   AttachmentMetadataDialog(attachment, { editAttachment = null }) { title, date, amount, contractor ->
    vm.updateAttachment(attachment, title, date, amount, contractor)
    editAttachment = null
   }
  }
 }
}

@Composable
private fun PhotoPreview(attachment: Attachment, vm: MainViewModel, close: () -> Unit) {
 var image by remember { mutableStateOf<ImageBitmap?>(null) }
 LaunchedEffect(attachment.entity.id) { image = vm.fullImage(attachment) }
 Dialog(onDismissRequest = close) {
  Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
   Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    image?.let { Image(it, attachment.displayName, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit) }
    IconButton(onClick = close, modifier = Modifier.align(Alignment.TopEnd)) { Icon(Icons.Default.Close, "Fermer", tint = Color.White) }
   }
  }
 }
}

@Composable
private fun PdfReaderDialog(attachment: Attachment, vm: MainViewModel, close: () -> Unit) {
 var page by remember { mutableIntStateOf(0) }
 var rendered by remember { mutableStateOf<PdfRender?>(null) }
 LaunchedEffect(attachment.entity.id, page) { rendered = vm.renderPdf(attachment, page) }
 Dialog(onDismissRequest = close) {
  Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
   Column(Modifier.fillMaxSize().padding(12.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
     IconButton(close) { Icon(Icons.Default.Close, "Fermer") }
     Text(attachment.title?.takeIf { it.isNotBlank() } ?: attachment.displayName, Modifier.weight(1f), maxLines = 1)
    }
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
     rendered?.image?.let { Image(it, "Page ${page + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        ?: CircularProgressIndicator()
    }
    val count = rendered?.pageCount ?: 1
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
     IconButton(onClick = { if (page > 0) page-- }, enabled = page > 0) { Icon(Icons.Default.ChevronLeft, "Page précédente") }
     Text("Page ${page + 1} / $count")
     IconButton(onClick = { if (page + 1 < count) page++ }, enabled = page + 1 < count) { Icon(Icons.Default.ChevronRight, "Page suivante") }
    }
   }
  }
 }
}

@Composable
private fun AttachmentMetadataDialog(attachment: Attachment, close: () -> Unit, save: (String, Long?, Long?, String?) -> Unit) {
 var title by remember { mutableStateOf(attachment.title ?: "") }
 var date by remember { mutableStateOf(attachment.documentDate?.let { SimpleDateFormat("dd/MM/yyyy", Locale.FRENCH).format(Date(it)) } ?: "") }
 var amount by remember { mutableStateOf(attachment.amountCents?.let { "%.2f".format(Locale.US, it / 100.0) } ?: "") }
 var contractor by remember { mutableStateOf(attachment.contractor ?: "") }
 AlertDialog(onDismissRequest = close, title = { Text("Informations du document") }, text = {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
   OutlinedTextField(title, { title = it }, label = { Text("Titre") }, singleLine = true)
   OutlinedTextField(date, { date = it }, label = { Text("Date (jj/mm/aaaa)") }, singleLine = true)
   OutlinedTextField(amount, { amount = it }, label = { Text("Montant (€)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
   OutlinedTextField(contractor, { contractor = it }, label = { Text("Artisan / entreprise") }, singleLine = true)
  }
 }, confirmButton = { TextButton(onClick = {
  val parsedDate = runCatching { SimpleDateFormat("dd/MM/yyyy", Locale.FRENCH).parse(date)?.time }.getOrNull()
  val parsedAmount = amount.replace(',', '.').toDoubleOrNull()?.let { (it * 100).toLong() }
  save(title, parsedDate, parsedAmount, contractor)
 }) { Text("Enregistrer") } }, dismissButton = { TextButton(close) { Text("Annuler") } })
}

@Composable
private fun ProjectDialog(close: () -> Unit, save: (String, String) -> Unit) {
 var t by remember { mutableStateOf("") }
 var c by remember { mutableStateOf("Travaux maison") }
 val presets = listOf(
  "Salle de bain" to "Travaux maison",
  "Cuisine" to "Travaux maison",
  "Toiture" to "Travaux maison",
  "Jardin" to "Extérieur",
  "Factures maison" to "Administratif"
 )

 AlertDialog(
  onDismissRequest = close,
  title = { Text("Nouveau projet") },
  text = {
   Column {
    Text("Projet prédéfini", fontWeight = FontWeight.Bold)
    presets.forEach { (name, cat) ->
     TextButton(onClick = { t = name; c = cat }) { Text(name) }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(t, { t = it }, label = { Text("Nom") })
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(c, { c = it }, label = { Text("Catégorie") })
   }
  },
  confirmButton = { Button({ if (t.isNotBlank()) save(t, c) }) { Text("Créer") } },
  dismissButton = { TextButton(close) { Text("Annuler") } }
 )
}

@Composable
private fun NoteDialog(save: (String, String) -> Unit, close: () -> Unit) {
 var t by remember { mutableStateOf("") }
 var b by remember { mutableStateOf("") }

 AlertDialog(
  onDismissRequest = close,
  title = { Text("Nouvelle note") },
  text = {
   Column {
    OutlinedTextField(t, { t = it }, label = { Text("Titre") })
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(b, { b = it }, label = { Text("Note") }, minLines = 4)
   }
  },
  confirmButton = { Button({ if (t.isNotBlank()) save(t, b) }) { Text("Enregistrer") } },
  dismissButton = { TextButton(close) { Text("Annuler") } }
 )
}

@Composable
private fun ProjectEditDialog(p: Project, close: () -> Unit, save: (String, String) -> Unit) {
 var t by remember { mutableStateOf(p.title) }
 var c by remember { mutableStateOf(p.entity.category) }

 AlertDialog(
  onDismissRequest = close,
  title = { Text("Modifier le projet") },
  text = {
   Column {
    OutlinedTextField(t, { t = it }, label = { Text("Nom") })
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(c, { c = it }, label = { Text("Catégorie") })
   }
  },
  confirmButton = { Button({ if (t.isNotBlank()) save(t, c) }) { Text("Enregistrer") } },
  dismissButton = { TextButton(close) { Text("Annuler") } }
 )
}

@Composable
private fun NoteEditDialog(n: Note, close: () -> Unit, save: (String, String) -> Unit) {
 var t by remember { mutableStateOf(n.title) }
 var b by remember { mutableStateOf(n.body) }

 AlertDialog(
  onDismissRequest = close,
  title = { Text("Modifier la note") },
  text = {
   Column {
    OutlinedTextField(t, { t = it }, label = { Text("Titre") })
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(b, { b = it }, label = { Text("Note") }, minLines = 4)
   }
  },
  confirmButton = { Button({ if (t.isNotBlank()) save(t, b) }) { Text("Enregistrer") } },
  dismissButton = { TextButton(close) { Text("Annuler") } }
 )
}

@Composable
private fun ConfirmDialog(title: String, message: String, yes: () -> Unit, no: () -> Unit) =
 AlertDialog(
  onDismissRequest = no,
  title = { Text(title) },
  text = { Text(message) },
  confirmButton = { Button(yes) { Text("Supprimer") } },
  dismissButton = { TextButton(no) { Text("Annuler") } }
 )
