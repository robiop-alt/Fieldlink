package no.fieldlink.uploader

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.documentfile.provider.DocumentFile

/** Persistent fail-closed binding. Never reassign an observed file to another customer. */
class ProjectLedger(ctx: Context) : SQLiteOpenHelper(ctx, "offset_ledger.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE media (uri TEXT PRIMARY KEY, size INTEGER NOT NULL, modified INTEGER NOT NULL, project TEXT, state TEXT NOT NULL, upload_id TEXT, received INTEGER NOT NULL DEFAULT 0)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
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
