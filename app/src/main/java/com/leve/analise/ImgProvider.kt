package com.leve.analise

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** Entrega as fotos da fila pro Threads (só leitura, só com permissão concedida no envio). */
class ImgProvider : ContentProvider() {
    override fun onCreate() = true

    private fun fileFor(uri: Uri): File {
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        val f = when {
            Regex("[a-z0-9]+\\.jpg").matches(name) -> File(PostQueue.dir(context!!), name)
            Regex("[a-z0-9]+\\.mp4").matches(name) -> File(VideoExport.videoDir(context!!), name) // vídeos prontos, direto do app
            else -> throw FileNotFoundException()
        }
        if (!f.exists()) throw FileNotFoundException()
        return f
    }

    override fun getType(uri: Uri) = if (uri.lastPathSegment?.endsWith(".mp4") == true) "video/mp4" else "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = fileFor(uri)
        val cols = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols).apply { addRow(arrayOf<Any>(f.name, f.length())) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
}
