package fr.bonobo.coffremaison
import android.app.Application
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.bonobo.coffremaison.data.*
import fr.bonobo.coffremaison.security.CryptoManager
import fr.bonobo.coffremaison.security.PortableBackupCrypto
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
data class Project(val entity:ProjectEntity,val title:String)
data class Note(val entity:NoteEntity,val title:String,val body:String)
data class Attachment(val entity:AttachmentEntity,val displayName:String,val mimeType:String,val title:String? = null,val documentDate:Long? = null,val amountCents:Long? = null,val contractor:String? = null)
data class PdfRender(val image:ImageBitmap,val pageCount:Int)
class MainViewModel(app:Application):AndroidViewModel(app){
 private val dao=(app as CoffreMaisonApp).db.dao(); private val crypto=CryptoManager()
 val errors=MutableSharedFlow<String>(extraBufferCapacity=1)
 private val thumbCache=HashMap<Long,ImageBitmap>()
 private data class PortableAttachment(val displayName:String,val mimeType:String,val title:String?,val date:Long?,val amount:Long?,val contractor:String?,val addedAt:Long,val bytes:ByteArray)
 private data class PortableNote(val title:String,val body:String,val updatedAt:Long)
 private data class PortableProject(val title:String,val category:String,val createdAt:Long,val updatedAt:Long,val notes:List<PortableNote>,val attachments:List<PortableAttachment>)
 suspend fun thumbnail(a:Attachment):ImageBitmap? = withContext(Dispatchers.IO) { if(!a.mimeType.startsWith("image/")) return@withContext null; thumbCache[a.entity.id]?.let{return@withContext it}; runCatching { val bytes=crypto.decryptBytes(File(File(getApplication<Application>().filesDir,"attachments"),a.entity.localFileName).readBytes()); val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}; BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds); var sample=1; while(bounds.outWidth/(sample*2)>=160&&bounds.outHeight/(sample*2)>=160) sample*=2; val bmp=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply{inSampleSize=sample}) ?: return@runCatching null; bmp.asImageBitmap().also{thumbCache[a.entity.id]=it} }.getOrNull() }
 suspend fun fullImage(a:Attachment):ImageBitmap? = withContext(Dispatchers.IO) { if(!a.mimeType.startsWith("image/")) return@withContext null; runCatching { val bytes=crypto.decryptBytes(File(File(getApplication<Application>().filesDir,"attachments"),a.entity.localFileName).readBytes()); BitmapFactory.decodeByteArray(bytes,0,bytes.size)?.asImageBitmap() }.getOrNull() }
 suspend fun renderPdf(a:Attachment,page:Int):PdfRender? = withContext(Dispatchers.IO) {
  if (!a.mimeType.equals("application/pdf", true)) return@withContext null
  runCatching {
   val app=getApplication<Application>(); val bytes=crypto.decryptBytes(File(File(app.filesDir,"attachments"),a.entity.localFileName).readBytes())
   val temp=File(app.cacheDir,"pdf_preview_${a.entity.id}.pdf"); temp.writeBytes(bytes)
   try {
    ParcelFileDescriptor.open(temp,ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
     PdfRenderer(fd).use { renderer ->
      if (renderer.pageCount==0) null else {
      val index=page.coerceIn(0,renderer.pageCount-1); renderer.openPage(index).use { source ->
       val width=1200; val height=(width.toFloat()*source.height/source.width).toInt().coerceAtLeast(1)
       val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888); bitmap.eraseColor(android.graphics.Color.WHITE)
       source.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
       PdfRender(bitmap.asImageBitmap(),renderer.pageCount)
      }
      }
     }
    }
   } finally { temp.delete() }
  }.getOrNull()
 }
 val projects=dao.projects().map { list->list.map { Project(it,runCatching { crypto.decrypt(it.titleCipher) }.getOrDefault("Projet inaccessible")) } }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5_000),emptyList())
 fun notes(id:Long)=dao.notes(id).map { list->list.map { Note(it,crypto.decrypt(it.titleCipher),crypto.decrypt(it.bodyCipher)) } }
 fun attachments(id:Long)=dao.attachments(id).map { list->list.map { a -> Attachment(a,crypto.decrypt(a.displayNameCipher),crypto.decrypt(a.mimeTypeCipher),a.titleCipher?.let { runCatching { crypto.decrypt(it) }.getOrNull() },a.documentDate,a.amountCents,a.contractorCipher?.let { runCatching { crypto.decrypt(it) }.getOrNull() }) } }
 fun addProject(title:String,category:String)=viewModelScope.launch { dao.addProject(ProjectEntity(titleCipher=crypto.encrypt(title.trim()),category=category)) }
 fun updateProject(project:Project,title:String,category:String)=viewModelScope.launch { dao.updateProject(project.entity.copy(titleCipher=crypto.encrypt(title.trim()),category=category.trim(),updatedAt=System.currentTimeMillis())) }
 fun addNote(projectId:Long,title:String,body:String)=viewModelScope.launch { dao.addNote(NoteEntity(projectId=projectId,titleCipher=crypto.encrypt(title.trim()),bodyCipher=crypto.encrypt(body.trim()))) }
 fun updateNote(note:Note,title:String,body:String)=viewModelScope.launch { dao.updateNote(note.entity.copy(titleCipher=crypto.encrypt(title.trim()),bodyCipher=crypto.encrypt(body.trim()),updatedAt=System.currentTimeMillis())) }
 fun deleteNote(note:Note)=viewModelScope.launch { dao.deleteNote(note.entity) }
 fun updateAttachment(a:Attachment,title:String,date:Long?,amountCents:Long?,contractor:String?)=viewModelScope.launch(Dispatchers.IO) { dao.updateAttachment(a.entity.copy(titleCipher=title.trim().takeIf { it.isNotEmpty() }?.let(crypto::encrypt),documentDate=date,amountCents=amountCents,contractorCipher=contractor?.trim()?.takeIf { it.isNotEmpty() }?.let(crypto::encrypt))) }
 fun addAttachment(projectId:Long,uri:Uri)=viewModelScope.launch(Dispatchers.IO) { try { val resolver=getApplication<Application>().contentResolver; val name=resolver.query(uri,null,null,null,null)?.use { c -> if(c.moveToFirst()){val i=c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if(i>=0) c.getString(i) else null}else null } ?: "Document"; val type=resolver.getType(uri) ?: if(name.endsWith(".pdf",true)) "application/pdf" else "image/*"; val bytes=resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("flux introuvable pour $uri"); val encrypted=crypto.encryptBytes(bytes); val fileName="${UUID.randomUUID()}.bin"; val folder=File(getApplication<Application>().filesDir,"attachments").apply { mkdirs() }; File(folder,fileName).writeBytes(encrypted); dao.addAttachment(AttachmentEntity(projectId=projectId,displayNameCipher=crypto.encrypt(name),mimeTypeCipher=crypto.encrypt(type),localFileName=fileName)) } catch(e:Exception) { errors.tryEmit("Ajout impossible : ${e.javaClass.simpleName} ${e.message?:""}") } }
 fun openAttachment(attachment:Attachment)=viewModelScope.launch(Dispatchers.IO) { try { val app=getApplication<Application>(); val clear=crypto.decryptBytes(File(File(app.filesDir,"attachments"),attachment.entity.localFileName).readBytes()); val safeName=attachment.displayName.replace(Regex("[^A-Za-z0-9._-]"),"_"); val out=File(app.cacheDir,"share_$safeName").apply { writeBytes(clear) }; val uri=FileProvider.getUriForFile(app,"${app.packageName}.files",out); val intent=Intent(Intent.ACTION_VIEW).setDataAndType(uri,attachment.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK); app.startActivity(Intent.createChooser(intent,"Ouvrir avec").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch(e:Exception) { errors.tryEmit("Ouverture impossible : ${e.javaClass.simpleName} ${e.message?:""}") } }
 fun deleteAttachment(attachment:Attachment)=viewModelScope.launch(Dispatchers.IO) { File(File(getApplication<Application>().filesDir,"attachments"),attachment.entity.localFileName).delete(); thumbCache.remove(attachment.entity.id); dao.deleteAttachment(attachment.entity) }
 fun delete(project:ProjectEntity)=viewModelScope.launch(Dispatchers.IO) { dao.attachmentsOnce(project.id).forEach { File(File(getApplication<Application>().filesDir,"attachments"),it.localFileName).delete() }; dao.deleteProject(project) }
 fun exportPortableBackup(uri:Uri,password:String)=viewModelScope.launch(Dispatchers.IO) {
  runCatching {
   require(password.length >= 8) { "Le mot de passe doit contenir au moins 8 caractères" }
   val app=getApplication<Application>(); val plain=ByteArrayOutputStream(); DataOutputStream(plain).use { out ->
    out.writeUTF("COFFRE_MAISON_PORTABLE_1")
    val projects=dao.projectsOnce(); out.writeInt(projects.size)
    projects.forEach { project ->
     out.writeUTF(crypto.decrypt(project.titleCipher)); out.writeUTF(project.category); out.writeLong(project.createdAt); out.writeLong(project.updatedAt)
     val notes=dao.notesOnce(project.id); out.writeInt(notes.size); notes.forEach { out.writeUTF(crypto.decrypt(it.titleCipher)); out.writeUTF(crypto.decrypt(it.bodyCipher)); out.writeLong(it.updatedAt) }
     val attachments=dao.attachmentsOnce(project.id); out.writeInt(attachments.size); attachments.forEach { a ->
      out.writeUTF(crypto.decrypt(a.displayNameCipher)); out.writeUTF(crypto.decrypt(a.mimeTypeCipher)); writeOptionalString(out,a.titleCipher?.let { runCatching { crypto.decrypt(it) }.getOrNull() }); writeOptionalLong(out,a.documentDate); writeOptionalLong(out,a.amountCents); writeOptionalString(out,a.contractorCipher?.let { runCatching { crypto.decrypt(it) }.getOrNull() }); out.writeLong(a.addedAt)
      val bytes=crypto.decryptBytes(File(File(app.filesDir,"attachments"),a.localFileName).readBytes()); out.writeInt(bytes.size); out.write(bytes)
     }
    }
   }
   app.contentResolver.openOutputStream(uri)?.use { it.write(PortableBackupCrypto.encrypt(plain.toByteArray(),password)) } ?: error("Dossier inaccessible")
   errors.tryEmit("Sauvegarde portable terminée")
  }.onFailure { errors.tryEmit("Sauvegarde impossible : ${it.message ?: it.javaClass.simpleName}") }
 }
 fun restorePortableBackup(uri:Uri,password:String)=viewModelScope.launch(Dispatchers.IO) {
  runCatching {
   val app=getApplication<Application>(); val encrypted=app.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Fichier inaccessible"); val plain=PortableBackupCrypto.decrypt(encrypted,password); val input=DataInputStream(ByteArrayInputStream(plain)); require(input.readUTF()=="COFFRE_MAISON_PORTABLE_1") { "Sauvegarde incompatible" }
   val count=input.readInt(); repeat(count) {
    val title=input.readUTF(); val category=input.readUTF(); val created=input.readLong(); val updated=input.readLong(); val projectId=dao.addProject(ProjectEntity(titleCipher=crypto.encrypt(title),category=category,createdAt=created,updatedAt=updated)); val noteCount=input.readInt(); repeat(noteCount) { dao.addNote(NoteEntity(projectId=projectId,titleCipher=crypto.encrypt(input.readUTF()),bodyCipher=crypto.encrypt(input.readUTF()),updatedAt=input.readLong())) }
    val attachmentCount=input.readInt(); repeat(attachmentCount) {
     val displayName=input.readUTF(); val mimeType=input.readUTF(); val attachmentTitle=readOptionalString(input); val date=readOptionalLong(input); val amount=readOptionalLong(input); val contractor=readOptionalString(input); val addedAt=input.readLong(); val size=input.readInt(); require(size>=0 && size<=100*1024*1024) { "Pièce jointe trop volumineuse" }; val bytes=ByteArray(size); input.readFully(bytes); val fileName="${UUID.randomUUID()}.bin"; val folder=File(app.filesDir,"attachments").apply { mkdirs() }; File(folder,fileName).writeBytes(crypto.encryptBytes(bytes)); dao.addAttachment(AttachmentEntity(projectId=projectId,displayNameCipher=crypto.encrypt(displayName),mimeTypeCipher=crypto.encrypt(mimeType),localFileName=fileName,addedAt=addedAt,titleCipher=attachmentTitle?.let(crypto::encrypt),documentDate=date,amountCents=amount,contractorCipher=contractor?.let(crypto::encrypt)))
    }
   }
   input.close(); errors.tryEmit("Restauration portable terminée")
  }.onFailure { errors.tryEmit("Restauration impossible : ${it.message ?: "mot de passe incorrect ou fichier invalide"}") }
 }
 private fun writeOptionalString(out:DataOutputStream,value:String?){ out.writeBoolean(value!=null); if(value!=null) out.writeUTF(value) }
 private fun writeOptionalLong(out:DataOutputStream,value:Long?){ out.writeBoolean(value!=null); if(value!=null) out.writeLong(value) }
 private fun readOptionalString(input:DataInputStream):String? = if(input.readBoolean()) input.readUTF() else null
 private fun readOptionalLong(input:DataInputStream):Long? = if(input.readBoolean()) input.readLong() else null
 fun exportProject(projectId:Long,uri:Uri)=viewModelScope.launch(Dispatchers.IO) {
  runCatching {
   val app=getApplication<Application>(); val project=dao.projectOnce(projectId) ?: error("Projet introuvable"); val notes=dao.notesOnce(projectId); val attachments=dao.attachmentsOnce(projectId); val zip=File(app.cacheDir,"project_export.zip")
   ZipOutputStream(BufferedOutputStream(zip.outputStream())).use { out ->
    out.putNextEntry(ZipEntry("projet.txt")); val writer=out.writer(Charsets.UTF_8)
     writer.appendLine("Projet : ${crypto.decrypt(project.titleCipher)}"); writer.appendLine("Catégorie : ${project.category}"); writer.appendLine(); writer.appendLine("NOTES")
     notes.forEach { writer.appendLine("\n## ${crypto.decrypt(it.titleCipher)}\n${crypto.decrypt(it.bodyCipher)}") }
     writer.appendLine("\nPIÈCES JOINTES")
     attachments.forEach { writer.appendLine("- ${crypto.decrypt(it.displayNameCipher)}") }
     writer.flush()
    out.closeEntry()
    attachments.forEachIndexed { index,a ->
     val name="${index + 1}_${crypto.decrypt(a.displayNameCipher).replace(Regex("[^A-Za-z0-9._-]"),"_")}"; out.putNextEntry(ZipEntry("pieces_jointes/$name")); crypto.decryptBytes(File(File(app.filesDir,"attachments"),a.localFileName).readBytes()).inputStream().use { it.copyTo(out) }; out.closeEntry()
    }
   }
   app.contentResolver.openOutputStream(uri)?.use { it.write(crypto.encryptBytes(zip.readBytes())) } ?: error("Dossier inaccessible"); zip.delete(); errors.tryEmit("Export du projet terminé")
  }.onFailure { errors.tryEmit("Export impossible : ${it.message ?: it.javaClass.simpleName}") }
 }
 fun exportBackup(uri:Uri)=viewModelScope.launch(Dispatchers.IO) {
  runCatching {
   val app=getApplication<Application>(); val db=(app as CoffreMaisonApp).db; db.close()
   val zip=File(app.cacheDir,"coffre_maison_backup.zip"); val dbFile=app.getDatabasePath("coffre_maison.db")
   ZipOutputStream(BufferedOutputStream(zip.outputStream())).use { out ->
    fun addFile(file:File,name:String){ if(!file.exists()) return; out.putNextEntry(ZipEntry(name)); file.inputStream().use { it.copyTo(out) }; out.closeEntry() }
    addFile(dbFile,"coffre_maison.db"); addFile(File(dbFile.path+"-wal"),"coffre_maison.db-wal"); addFile(File(dbFile.path+"-shm"),"coffre_maison.db-shm")
    File(app.filesDir,"attachments").listFiles()?.filter { it.isFile }?.forEach { addFile(it,"attachments/${it.name}") }
   }
   val encrypted=crypto.encryptBytes(zip.readBytes()); app.contentResolver.openOutputStream(uri)?.use { it.write(encrypted) } ?: error("Dossier inaccessible")
   zip.delete(); errors.tryEmit("Sauvegarde chiffrée terminée")
  }.onFailure { errors.tryEmit("Sauvegarde impossible : ${it.message ?: it.javaClass.simpleName}") }
 }
 fun restoreBackup(uri:Uri)=viewModelScope.launch(Dispatchers.IO) {
  runCatching {
   val app=getApplication<Application>(); val encrypted=app.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Fichier inaccessible")
   val zip=File(app.cacheDir,"coffre_maison_restore.zip"); zip.writeBytes(crypto.decryptBytes(encrypted)); (app as CoffreMaisonApp).db.close()
   val databaseDir=app.getDatabasePath("coffre_maison.db").parentFile ?: error("Dossier base inaccessible"); databaseDir.mkdirs()
   ZipInputStream(BufferedInputStream(zip.inputStream())).use { input ->
    var entry=input.nextEntry
    while(entry != null){
     val target=when { entry.name=="coffre_maison.db" -> app.getDatabasePath("coffre_maison.db"); entry.name=="coffre_maison.db-wal" -> File(app.getDatabasePath("coffre_maison.db").path+"-wal"); entry.name=="coffre_maison.db-shm" -> File(app.getDatabasePath("coffre_maison.db").path+"-shm"); entry.name.startsWith("attachments/") && !entry.name.contains("..") -> File(File(app.filesDir,"attachments"),entry.name.removePrefix("attachments/")); else -> null }
     if(target != null){ target.parentFile?.mkdirs(); target.outputStream().use { input.copyTo(it) } }
     input.closeEntry(); entry=input.nextEntry
    }
   }
   zip.delete(); errors.tryEmit("Restauration terminée : redémarre l'application")
  }.onFailure { errors.tryEmit("Restauration impossible : ${it.message ?: it.javaClass.simpleName}") }
 }
}
