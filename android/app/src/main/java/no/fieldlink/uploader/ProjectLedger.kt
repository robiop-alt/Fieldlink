package no.fieldlink.uploader

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.documentfile.provider.DocumentFile

/** Persistent fail-closed binding. Never reassign an observed file to another customer. */
class ProjectLedger(ctx: Context) : SQLiteOpenHelper(ctx, "offset_ledger.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE media (uri TEXT PRIMARY KEY, size INTEGER NOT NULL, modified INTEGER NOT NULL, project TEXT, state TEXT NOT NULL, upload_id TEXT, received INTEGER NOT NULL DEFAULT 0)")
        createHistory(db)
        createQueue(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if(oldVersion<2) createHistory(db)
        if(oldVersion<3) createQueue(db)
    }

    private fun createQueue(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS clip_queue (uri TEXT PRIMARY KEY, project TEXT NOT NULL, priority INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL)")
    }
    private fun addToQueue(db:SQLiteDatabase,uri:String,project:String) {
        db.execSQL("INSERT OR REPLACE INTO clip_queue(uri,project,priority,created) VALUES(?,?,0,?)",arrayOf(uri,project,System.currentTimeMillis()))
    }
    @Synchronized fun prioritize(uri:String,project:String) {
        writableDatabase.execSQL("UPDATE clip_queue SET priority=(SELECT COALESCE(MAX(priority),0)+1 FROM clip_queue) WHERE uri=? AND project=?",arrayOf(uri,project))
    }
    @Synchronized fun removeFromQueue(uri:String,project:String) {
        writableDatabase.execSQL("DELETE FROM clip_queue WHERE uri=? AND project=?",arrayOf(uri,project))
        writableDatabase.execSQL("UPDATE media SET state='quarantine',upload_id=NULL,received=0 WHERE uri=? AND project=? AND state='pending'",arrayOf(uri,project))
    }
    @Synchronized fun stateFor(uri:String,project:String):String {
        readableDatabase.rawQuery("SELECT project,state FROM media WHERE uri=?",arrayOf(uri)).use { row ->
            if(!row.moveToFirst()) return "new"
            val owner=if(row.isNull(0)) null else row.getString(0)
            return if(owner!=null && owner!=project) "other" else row.getString(1)
        }
    }
    @Synchronized fun excludeFromAuto(f:DocumentFile,project:String) {
        if(project.isBlank() || !f.isFile) return
        val uri=f.uri.toString()
        val db=writableDatabase
        db.rawQuery("SELECT project FROM media WHERE uri=?",arrayOf(uri)).use { row ->
            if(row.moveToFirst()) {
                if(!row.isNull(0) && row.getString(0)!=project) return
                db.execSQL("UPDATE media SET state='quarantine',project=?,size=?,modified=?,upload_id=NULL,received=0 WHERE uri=?",
                    arrayOf(project,f.length(),f.lastModified(),uri))
            }else{
                db.execSQL("INSERT INTO media(uri,size,modified,project,state) VALUES(?,?,?,?,'quarantine')",
                    arrayOf(uri,f.length(),f.lastModified(),project))
            }
        }
        db.execSQL("DELETE FROM clip_queue WHERE uri=? AND project=?",arrayOf(uri,project))
    }
    @Synchronized fun queueUris(project:String):Set<String> {
        val result=mutableSetOf<String>()
        readableDatabase.rawQuery("SELECT uri FROM clip_queue WHERE project=?",arrayOf(project)).use { rows -> while(rows.moveToNext()) result.add(rows.getString(0)) }
        return result
    }
    private fun createHistory(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS upload_history (id INTEGER PRIMARY KEY AUTOINCREMENT, project TEXT NOT NULL, filename TEXT NOT NULL, status TEXT NOT NULL, detail TEXT, created INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_project ON upload_history(project,created DESC)")
    }
    data class HistoryItem(val filename:String,val status:String,val detail:String,val created:Long)
    @Synchronized fun logEvent(project:String,filename:String,status:String,detail:String="") {
        if(project.isBlank()) return
        val db=writableDatabase
        db.execSQL("INSERT INTO upload_history(project,filename,status,detail,created) VALUES(?,?,?,?,?)",
            arrayOf(project,filename.take(180),status,detail.take(180),System.currentTimeMillis()))
        db.execSQL("DELETE FROM upload_history WHERE id NOT IN (SELECT id FROM upload_history ORDER BY id DESC LIMIT 500)")
    }
    @Synchronized fun history(project:String,limit:Int=50):List<HistoryItem> {
        val items=mutableListOf<HistoryItem>()
        readableDatabase.rawQuery("SELECT filename,status,COALESCE(detail,''),created FROM upload_history WHERE project=? ORDER BY id DESC LIMIT ?",
            arrayOf(project,limit.coerceIn(1,100).toString())).use { rows ->
            while(rows.moveToNext()) items.add(HistoryItem(rows.getString(0),rows.getString(1),rows.getString(2),rows.getLong(3)))
        }
        return items
    }
    @Synchronized fun counts(project:String):Triple<Int,Int,Int> {
        readableDatabase.rawQuery("SELECT SUM(CASE WHEN state='done' THEN 1 ELSE 0 END),SUM(CASE WHEN state='pending' THEN 1 ELSE 0 END),SUM(CASE WHEN state='quarantine' THEN 1 ELSE 0 END) FROM media WHERE project=?",
            arrayOf(project)).use { rows ->
            rows.moveToFirst()
            return Triple(if(rows.isNull(0)) 0 else rows.getInt(0),if(rows.isNull(1)) 0 else rows.getInt(1),if(rows.isNull(2)) 0 else rows.getInt(2))
        }
    }
    @Synchronized fun registerExisting(files: List<DocumentFile>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (f in files) {
                db.execSQL("INSERT OR IGNORE INTO media(uri,size,modified,project,state) VALUES(?,?,?,NULL,'quarantine')", arrayOf(f.uri.toString(),f.length(),f.lastModified()))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun observe(f: DocumentFile, activeProject: String, allowNew: Boolean): String {
        val key=f.uri.toString(); val size=f.length(); val modified=f.lastModified()
        val db=writableDatabase
        db.rawQuery("SELECT size,modified,project,state FROM media WHERE uri=?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) {
                val oldSize=c.getLong(0); val oldModified=c.getLong(1)
                val owner=if(c.isNull(2)) null else c.getString(2)
                val state=c.getString(3)
                // A reused URI or modified file is not safe to automatically reassign.
                if (size!=oldSize || modified!=oldModified) {
                    db.execSQL("UPDATE media SET size=?,modified=?,state='quarantine',upload_id=NULL,received=0 WHERE uri=?", arrayOf(size,modified,key))
                    return "quarantine"
                }
                return if (owner==activeProject) state else "other"
            }
        }
        if (!allowNew) {
            db.execSQL("INSERT OR IGNORE INTO media(uri,size,modified,project,state) VALUES(?,?,?,NULL,'quarantine')", arrayOf(key,size,modified))
            return "quarantine"
        }
        db.execSQL("INSERT OR IGNORE INTO media(uri,size,modified,project,state) VALUES(?,?,?,?, 'pending')", arrayOf(key,size,modified,activeProject))
        return "pending"
    }
    @Synchronized fun enqueueManual(f: DocumentFile, project: String): String {
        if(project.isBlank() || !f.isFile || f.length()<=0L) return "Invalid file"
        val key=f.uri.toString()
        val db=writableDatabase
        db.rawQuery("SELECT project,state,size,modified FROM media WHERE uri=?",arrayOf(key)).use { row ->
            if(row.moveToFirst()) {
                val owner=if(row.isNull(0)) null else row.getString(0)
                val state=row.getString(1)
                if(owner!=null && owner!=project) return "File belongs to another project"
                if(state=="done") return "Already uploaded"
                if(state=="pending" && owner==project) return "Already queued"
                db.execSQL("UPDATE media SET project=?,state='pending',size=?,modified=?,upload_id=NULL,received=0 WHERE uri=?",
                    arrayOf(project,f.length(),f.lastModified(),key))
                addToQueue(db,key,project)
                return "Queued"
            }
        }
        db.execSQL("INSERT INTO media(uri,size,modified,project,state) VALUES(?,?,?,?,'pending')",
            arrayOf(key,f.length(),f.lastModified(),project))
        addToQueue(db,key,project)
        return "Queued"
    }
    @Synchronized fun manualPending(project:String):List<String> {
        val result=mutableListOf<String>()
        readableDatabase.rawQuery("SELECT m.uri FROM media m JOIN clip_queue q ON q.uri=m.uri AND q.project=m.project WHERE m.project=? AND m.state='pending' ORDER BY q.priority DESC,q.created ASC",arrayOf(project)).use { rows ->
            while(rows.moveToNext()) result.add(rows.getString(0))
        }
        return result
    }
    @Synchronized fun markDone(f: DocumentFile, project: String) {
        writableDatabase.execSQL("DELETE FROM clip_queue WHERE uri=? AND project=?",arrayOf(f.uri.toString(),project))
        writableDatabase.execSQL("UPDATE media SET state='done',upload_id=NULL,received=0 WHERE uri=? AND project=? AND state='pending'", arrayOf(f.uri.toString(),project))
    }
    @Synchronized fun getUploadId(f: DocumentFile, project: String): String? {
        readableDatabase.rawQuery("SELECT upload_id FROM media WHERE uri=? AND project=? AND state='pending'", arrayOf(f.uri.toString(),project)).use { c ->
            return if(c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    }
    @Synchronized fun setUploadId(f: DocumentFile, project: String, id: String) {
        writableDatabase.execSQL("UPDATE media SET upload_id=? WHERE uri=? AND project=? AND state='pending'", arrayOf(id,f.uri.toString(),project))
    }
    @Synchronized fun clearUploadId(f: DocumentFile, project: String) {
        writableDatabase.execSQL("UPDATE media SET upload_id=NULL WHERE uri=? AND project=? AND state='pending'", arrayOf(f.uri.toString(),project))
    }
    @Synchronized fun countQuarantined(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM media WHERE state='quarantine'", null).use { c -> c.moveToFirst(); return c.getInt(0) }
    }
}
