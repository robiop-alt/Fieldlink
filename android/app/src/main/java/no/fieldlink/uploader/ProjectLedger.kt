package no.fieldlink.uploader

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.documentfile.provider.DocumentFile

/** Persistent fail-closed binding. Never reassign an observed file to another customer. */
class ProjectLedger(ctx: Context) : SQLiteOpenHelper(ctx, "offset_ledger.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE media (uri TEXT PRIMARY KEY, size INTEGER NOT NULL, modified INTEGER NOT NULL, project TEXT, state TEXT NOT NULL, upload_id TEXT, received INTEGER NOT NULL DEFAULT 0)")
        createHistory(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if(oldVersion<2) createHistory(db)
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
            return Triple(rows.getInt(0),rows.getInt(1),rows.getInt(2))
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
    @Synchronized fun markDone(f: DocumentFile, project: String) {
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
