package com.kaanelloed.iconeration.packages

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import com.kaanelloed.iconeration.data.InstalledApplication
import com.kaanelloed.iconeration.drawable.DrawableExtension
import com.kaanelloed.iconeration.drawable.DrawableExtension.Companion.shrinkIfBiggerThan
import com.kaanelloed.iconeration.icon.EmptyIcon
import com.kaanelloed.iconeration.icon.ExportableIcon
import java.text.Normalizer

class PackageInfoStruct(
    val appName: String,
    val packageName: String,
    val activityName: String,
    val icon: Drawable,
    val iconID: Int,
    val createdIcon: ExportableIcon = EmptyIcon(),
    val internalVersion: Int = 0,
    private val cachedListBitmap: Bitmap? = null
) : Comparable<PackageInfoStruct> {

    /**
     * Lazily computed and cached bitmap for display in the app list.
     * Cached at the data layer so it survives LazyColumn composable disposal
     * during scrolling, avoiding repeated bitmap allocations and GC pressure.
     *
     * When created via [changeExport], the already-computed bitmap is passed
     * through [cachedListBitmap] to avoid re-allocating 500+ bitmaps during
     * bulk refresh operations.
     */
    val listBitmap: Bitmap by lazy {
        cachedListBitmap ?: icon.shrinkIfBiggerThan(DrawableExtension.MAX_ICON_LIST_SIZE)
    }
    override fun equals(other: Any?): Boolean {
        if (other is PackageInfoStruct) {
            return packageName == other.packageName && activityName == other.activityName && other.internalVersion == internalVersion
        }

        return false
    }

    override fun compareTo(other: PackageInfoStruct): Int = when {
        this.appName != other.appName -> this.normalizeName().lowercase() compareTo other.normalizeName().lowercase() // compareTo() in the infix form
        else -> 0
    }

    fun changeExport(
        createdIcon: ExportableIcon
    ): PackageInfoStruct {
        return PackageInfoStruct(appName, packageName, activityName, icon, iconID, createdIcon, internalVersion + 1, listBitmap)
    }

    fun getFileName(): String {
        return packageName.replace('.', '_')
    }

    fun toInstalledApplication(): InstalledApplication {
        return InstalledApplication(packageName, activityName, iconID)
    }

    private fun normalizeName(): String {
        return removeDiacritics(appName)
    }

    private fun removeDiacritics(text: String): String {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replace(DIACRITICS_REGEX, "")
    }

    override fun hashCode(): Int {
        var result = packageName.hashCode()
        result = 31 * result + activityName.hashCode()
        return result
    }

    companion object {
        // Pre-compiled regex to avoid recompiling on every compareTo call
        // during sorting of 500+ items (O(n log n) calls).
        private val DIACRITICS_REGEX = "\\p{Mn}+".toRegex()
    }
}