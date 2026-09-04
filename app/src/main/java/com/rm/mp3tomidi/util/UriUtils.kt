package com.rm.mp3tomidi.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile

fun displayNameOf(context: Context, uri: Uri): String? {
    // A tree Uri (from OpenDocumentTree) isn't a queryable document Uri -- querying it directly
    // throws UnsupportedOperationException. DocumentFile resolves the tree's root document instead.
    if (DocumentsContract.isTreeUri(uri)) {
        return DocumentFile.fromTreeUri(context, uri)?.name
    }
    return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
}
