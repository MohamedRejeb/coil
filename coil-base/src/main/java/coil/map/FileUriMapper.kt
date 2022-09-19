package coil.map

import android.content.ContentResolver.SCHEME_FILE
import android.net.Uri
import coil.request.Options
import coil.util.firstPathSegment
import coil.util.isAssetUri
import java.io.File

internal class FileUriMapper : Mapper<Uri, File> {

    override fun map(data: Uri, options: Options): File? {
        if (!isApplicable(data)) return null
        val uri = if (data.scheme == null) data else data.buildUpon().scheme(null).build()
        return File(uri.toString())
    }

    private fun isApplicable(data: Uri): Boolean {
        return !isAssetUri(data) &&
            data.scheme.let { it == null || it == SCHEME_FILE } &&
            data.path.orEmpty().startsWith('/') && data.firstPathSegment != null
    }
}
