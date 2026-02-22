package com.kaanelloed.iconeration.apk

import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import com.kaanelloed.iconeration.R
import com.kaanelloed.iconeration.constants.SuppressRedundantSuspendModifier
import com.kaanelloed.iconeration.data.AlchemiconPackDatabase
import com.kaanelloed.iconeration.data.BackgroundColorKey
import com.kaanelloed.iconeration.data.CalendarIconsKey
import com.kaanelloed.iconeration.data.DbApplication
import com.kaanelloed.iconeration.data.ExportThemedKey
import com.kaanelloed.iconeration.data.IMAGE_EDIT_DEFAULT
import com.kaanelloed.iconeration.data.IconColorKey
import com.kaanelloed.iconeration.data.IconPack
import com.kaanelloed.iconeration.data.IncludeVectorKey
import com.kaanelloed.iconeration.data.InstalledApplication
import com.kaanelloed.iconeration.data.MonochromeKey
import com.kaanelloed.iconeration.data.OverrideIconKey
import com.kaanelloed.iconeration.data.PrimaryIconPackKey
import com.kaanelloed.iconeration.data.PrimaryImageEditKey
import com.kaanelloed.iconeration.data.PrimarySourceKey
import com.kaanelloed.iconeration.data.PrimaryTextTypeKey
import com.kaanelloed.iconeration.data.RawElement
import com.kaanelloed.iconeration.data.SOURCE_DEFAULT
import com.kaanelloed.iconeration.data.SecondaryIconPackKey
import com.kaanelloed.iconeration.data.SecondaryImageEditKey
import com.kaanelloed.iconeration.data.SecondarySourceKey
import com.kaanelloed.iconeration.data.SecondaryTextTypeKey
import com.kaanelloed.iconeration.data.TEXT_TYPE_DEFAULT
import com.kaanelloed.iconeration.data.getBooleanValue
import com.kaanelloed.iconeration.data.getColorValue
import com.kaanelloed.iconeration.data.getDefaultBackgroundColor
import com.kaanelloed.iconeration.data.getDefaultIconColor
import com.kaanelloed.iconeration.data.getEnumValue
import com.kaanelloed.iconeration.data.getStringValue
import com.kaanelloed.iconeration.drawable.DrawableExtension.Companion.sizeIsGreaterThanZero
import com.kaanelloed.iconeration.drawable.ResourceDrawable
import com.kaanelloed.iconeration.extension.bitmapFromBase64
import com.kaanelloed.iconeration.extension.forEachBatch
import com.kaanelloed.iconeration.icon.BitmapIcon
import com.kaanelloed.iconeration.icon.EmptyIcon
import com.kaanelloed.iconeration.icon.ExportableIcon
import com.kaanelloed.iconeration.icon.VectorIcon
import com.kaanelloed.iconeration.icon.creator.GenerationOptions
import com.kaanelloed.iconeration.icon.creator.IconGenerator
import com.kaanelloed.iconeration.icon.creator.IconPackContainer
import com.kaanelloed.iconeration.packages.ApplicationManager
import com.kaanelloed.iconeration.packages.PackageInfoStruct
import com.kaanelloed.iconeration.ui.supportDynamicColors
import com.kaanelloed.iconeration.ui.toHexString
import com.kaanelloed.iconeration.ui.toInt
import com.kaanelloed.iconeration.vector.VectorParser
import com.kaanelloed.iconeration.xml.XmlDecoder

class ApplicationProvider(private val context: Context) {
    var applicationList: List<PackageInfoStruct> by mutableStateOf(listOf())
        private set
    var iconPacks: List<IconPack> by mutableStateOf(listOf())
        private set
    var iconPackLoaded: Boolean by mutableStateOf(false)
        private set

    private var iconPackAppFilterElement: Map<IconPack, List<RawElement>> = emptyMap()
    private var installedApplications: List<InstalledApplication> = listOf()
    private var calendarIcon: Map<InstalledApplication, String> = mapOf()
    private var calendarIconsDrawable: Map<String, Drawable> = emptyMap()

    var defaultColor: Color = Color.Unspecified

    private var am: ApplicationManager? = null
    private val appManager: ApplicationManager
        get() {
            if (am == null) am = ApplicationManager(context)
            return am!!
        }

    suspend fun initialize() {
        initializeApplications()
        initializeIconPacks()
        initializeAlchemiconPack()
    }

    fun initializeApplications() {
        val apps = appManager.getAllInstalledApps()
        apps.sort()

        val appList = apps.toList()

        // Pre-initialize list bitmaps on this (background) thread so they
        // don't get lazily initialized on the main thread during scroll,
        // which causes frame drops and OOM crashes with 500+ apps.
        preWarmListBitmaps(appList)

        applicationList = appList
    }

    @Suppress(SuppressRedundantSuspendModifier)
    suspend fun initializeIconPacks() {
        iconPackLoaded = false
        iconPacks = appManager.getIconPacks()
        getAppFilterElements()
    }

    suspend fun initializeAlchemiconPack() {
        loadAlchemiconPack()
    }

    fun retrieveOtherIcons(preferences: Preferences) {
        val iconPackageName = preferences.getStringValue(PrimaryIconPackKey)
        val retrieveCalendarIcon = preferences.getBooleanValue(CalendarIconsKey)

        if (iconPackageName != "" && retrieveCalendarIcon) {
            retrieveCalendarIcons(iconPackageName)
        }
    }

    fun refreshIcon(application: PackageInfoStruct, preferences: Preferences) {
        val primarySource = preferences.getEnumValue(PrimarySourceKey, SOURCE_DEFAULT)
        val primaryImageEdit = preferences.getEnumValue(PrimaryImageEditKey, IMAGE_EDIT_DEFAULT)
        val primaryTextType = preferences.getEnumValue(PrimaryTextTypeKey, TEXT_TYPE_DEFAULT)
        val primaryIconPack = preferences.getStringValue(PrimaryIconPackKey)
        val secondarySource = preferences.getEnumValue(SecondarySourceKey, SOURCE_DEFAULT)
        val secondaryImageEdit = preferences.getEnumValue(SecondaryImageEditKey, IMAGE_EDIT_DEFAULT)
        val secondaryTextType = preferences.getEnumValue(SecondaryTextTypeKey, TEXT_TYPE_DEFAULT)
        val secondaryIconPack = preferences.getStringValue(SecondaryIconPackKey)
        val monochrome = preferences.getBooleanValue(MonochromeKey)
        val vector = preferences.getBooleanValue(IncludeVectorKey)
        val iconColorValue = preferences.getColorValue(IconColorKey
            , preferences.getDefaultIconColor(context))
        val bgColorValue = preferences.getColorValue(BackgroundColorKey
            , preferences.getDefaultBackgroundColor(context))
        val themed = preferences.getBooleanValue(ExportThemedKey)

        val genOptions = GenerationOptions(
            primarySource
            , primaryImageEdit
            , primaryTextType
            , primaryIconPack
            , secondarySource
            , secondaryImageEdit
            , secondaryTextType
            , secondaryIconPack
            , iconColorValue.toInt()
            , bgColorValue.toInt()
            , vector
            , monochrome
            , themed
            , true)

        refreshIcon(application, genOptions)
    }

    private fun refreshIcon(application: PackageInfoStruct, options: GenerationOptions) {
        val primaryIconPackApps = getIconPackAppDrawables(options.primaryIconPack)
        val secondaryIconPackApps = getIconPackAppDrawables(options.secondaryIconPack)

        val pack1 = IconPackContainer(options.primaryIconPack, primaryIconPackApps)
        val pack2 = IconPackContainer(options.secondaryIconPack, secondaryIconPackApps)

        val builder = IconGenerator(context, options, pack1, pack2)
        builder.generateIcon(application) { app, icon ->
            editApplication(app, app.changeExport(icon))
        }
    }

    fun refreshIcons(preferences: Preferences) {
        val primarySource = preferences.getEnumValue(PrimarySourceKey, SOURCE_DEFAULT)
        val primaryImageEdit = preferences.getEnumValue(PrimaryImageEditKey, IMAGE_EDIT_DEFAULT)
        val primaryTextType = preferences.getEnumValue(PrimaryTextTypeKey, TEXT_TYPE_DEFAULT)
        val primaryIconPack = preferences.getStringValue(PrimaryIconPackKey)
        val secondarySource = preferences.getEnumValue(SecondarySourceKey, SOURCE_DEFAULT)
        val secondaryImageEdit = preferences.getEnumValue(SecondaryImageEditKey, IMAGE_EDIT_DEFAULT)
        val secondaryTextType = preferences.getEnumValue(SecondaryTextTypeKey, TEXT_TYPE_DEFAULT)
        val secondaryIconPack = preferences.getStringValue(SecondaryIconPackKey)
        val monochrome = preferences.getBooleanValue(MonochromeKey)
        val vector = preferences.getBooleanValue(IncludeVectorKey)
        val iconColorValue = preferences.getColorValue(IconColorKey
            , preferences.getDefaultIconColor(context))
        val bgColorValue = preferences.getColorValue(BackgroundColorKey
            , preferences.getDefaultBackgroundColor(context))
        val themed = preferences.getBooleanValue(ExportThemedKey)
        val dynamicColor = themed && supportDynamicColors()
        val retrieveCalendarIcon = preferences.getBooleanValue(CalendarIconsKey)
        val overrideIcon = preferences.getBooleanValue(OverrideIconKey)

        val primaryIconPackApps = getIconPackAppDrawables(primaryIconPack)
        val secondaryIconPackApps = getIconPackAppDrawables(secondaryIconPack)

        if (primaryIconPack != "" && retrieveCalendarIcon) {
            retrieveCalendarIcons(primaryIconPack)
        }

        var iconColor = iconColorValue.toInt()
        var bgColor = bgColorValue.toInt()

        if (dynamicColor) {
            iconColor = context.resources.getColor(R.color.icon_color, null)
            bgColor = context.resources.getColor(R.color.icon_background_color, null)
        }

        val opt = GenerationOptions(
            primarySource,
            primaryImageEdit,
            primaryTextType,
            primaryIconPack,
            secondarySource,
            secondaryImageEdit,
            secondaryTextType,
            secondaryIconPack,
            iconColor,
            bgColor,
            vector,
            monochrome,
            themed,
            overrideIcon
        )

        val pack1 = IconPackContainer(primaryIconPack, primaryIconPackApps)
        val pack2 = IconPackContainer(secondaryIconPack, secondaryIconPackApps)

        val builder = IconGenerator(context, opt, pack1, pack2)

        // Process apps in batches and commit each batch immediately via
        // editApplicationsBatch.  Accumulating all edits before committing
        // (the previous approach) held all new ExportableIcon/PackageInfoStruct
        // objects alive in allEdits while the old ones were still referenced by
        // applicationList, doubling peak icon-bitmap memory for 500+ apps.
        //
        // Committing per-batch lets the GC reclaim replaced icons incrementally:
        // after each editApplicationsBatch call the old PackageInfoStruct objects
        // for that batch are unreferenced and eligible for collection before the
        // next batch starts generating new ones.
        //
        // The Compose recompositions triggered per batch are asynchronous — they
        // run on the main thread while this background thread keeps generating
        // icons without waiting.  Each batch list-copy is only 8 bytes × N (pointer
        // array), so the transient allocation per batch is negligible compared to
        // the peak savings of not holding all icons simultaneously.
        applicationList.forEachBatch { batchStart, batch ->
            val batchIndexMap = HashMap<PackageInfoStruct, Int>(batch.size)
            for (i in batch.indices) {
                batchIndexMap[batch[i]] = batchStart + i
            }

            val batchEdits = mutableListOf<Pair<Int, PackageInfoStruct>>()
            builder.generateIcons(batch) { application, icon ->
                val index = batchIndexMap[application]
                if (index != null) {
                    batchEdits.add(Pair(index, application.changeExport(icon)))
                }
            }
            editApplicationsBatch(batchEdits)
        }
    }

    fun getIcon(application: PackageInfoStruct, options: GenerationOptions, customIcon: ResourceDrawable? = null): ExportableIcon {
        var icon: ExportableIcon = EmptyIcon()

        val primaryIconPackApps = getIconPackAppDrawables(options.primaryIconPack)

        val pack1 = IconPackContainer(options.primaryIconPack, primaryIconPackApps)
        val pack2 = IconPackContainer("", emptyMap())

        val builder = IconGenerator(context, options, pack1, pack2)
        builder.generateIcon(application, customIcon) { _, newIcon ->
            icon = newIcon
        }

        return icon
    }

    fun buildAndSignIconPack(preferences: Preferences, textMethod: (text: String) -> Unit): BuiltIconPack {
        val themed = preferences.getBooleanValue(ExportThemedKey)
        val iconColor = preferences.getDefaultIconColor(context)
        val bgColor = preferences.getDefaultBackgroundColor(context)

        val iconPackGenerator = IconPackBuilder(
            context,
            applicationList,
            calendarIcon,
            calendarIconsDrawable
        )
        val canBeInstalled = iconPackGenerator.canBeInstalled() // must be called before build and sign
        val apk = iconPackGenerator.buildAndSign(themed, iconColor.toHexString(), bgColor.toHexString(), textMethod)

        return BuiltIconPack(apk, iconPackGenerator.getIconPackName(), canBeInstalled)
    }

    suspend fun installIconPack(iconPack: BuiltIconPack): Boolean {
        var success = false

        if (iconPack.canBeInstalled) {
            success = ApkInstaller(context).install(iconPack.uri)
        } else {
            if (ApkUninstaller(context).uninstall(iconPack.packageName)) {
                success = ApkInstaller(context).install(iconPack.uri)
            }
        }

        saveAlchemiconPack()

        return success
    }

    private fun retrieveCalendarIcons(iconPackageName: String) {
        val appMan = ApplicationManager(context)
        val entry = iconPackAppFilterElement.entries.find { it.key.packageName == iconPackageName }

        val packApps = entry?.value ?: listOf()
        calendarIcon = appMan.getCalendarApplications(installedApplications, packApps)
        calendarIconsDrawable =
            appMan.getCalendarFromAppFilterElements(
                iconPackageName,
                packApps
            )
    }

    @Suppress(SuppressRedundantSuspendModifier)
    private suspend fun loadAlchemiconPack() {
        val db = Room.databaseBuilder(
            context,
            AlchemiconPackDatabase::class.java, "alchemiconPack"
        ).build()

        val dao = db.alchemiconPackDao()

        val dbApps = dao.getAll()
        val apps = applicationList.toList() //clone

        // Build a lookup map for O(1) access instead of O(n) find per app
        val dbAppMap = HashMap<Pair<String, String>, DbApplication>(dbApps.size)
        for (dbApp in dbApps) {
            dbAppMap[Pair(dbApp.packageName, dbApp.activityName)] = dbApp
        }

        // Collect all edits and apply as a single batch to avoid
        // creating a new list copy for each of 500+ apps
        val edits = mutableListOf<Pair<Int, PackageInfoStruct>>()

        for ((index, app) in apps.withIndex()) {
            val dbApp = dbAppMap[Pair(app.packageName, app.activityName)] ?: continue

            val icon = if (dbApp.isXml) {
                val nodes = XmlDecoder.fromBase64(dbApp.drawable)
                val vector = VectorParser.parse(context.resources, nodes, defaultColor)

                if (vector != null) {
                    VectorIcon(vector)
                } else {
                    EmptyIcon()
                }
            } else {
                BitmapIcon(bitmapFromBase64(dbApp.drawable), dbApp.isAdaptiveIcon)
            }

            edits.add(Pair(index, app.changeExport(icon)))
        }

        preWarmEditBitmaps(edits)
        editApplicationsBatch(edits)

        db.close()
    }

    private fun saveAlchemiconPack() {
        val db = Room.databaseBuilder(
            context,
            AlchemiconPackDatabase::class.java, "alchemiconPack"
        ).build()

        val packDao = db.alchemiconPackDao()

        // Delete all existing entries first
        packDao.deleteAllApplications()

        // Process apps in batches to avoid OOM with many installed apps.
        // Converting icons to Base64 strings is memory-intensive.
        val appsWithIcons = applicationList.filter { it.createdIcon !is EmptyIcon }

        appsWithIcons.forEachBatch { _, batch ->
            val dbApps = batch.map { app ->
                val isXml = app.createdIcon is VectorIcon
                DbApplication(
                    app.packageName,
                    app.activityName,
                    app.createdIcon.exportAsAdaptiveIcon,
                    isXml,
                    app.createdIcon.toDbString()
                )
            }

            packDao.insertAll(dbApps)
        }

        db.close()
    }

    /**
     * Pre-initialize listBitmap for all apps so the lazy property is resolved
     * on a background thread rather than on the main thread during scroll.
     */
    private fun preWarmListBitmaps(apps: List<PackageInfoStruct>) {
        for (app in apps) {
            if (app.icon.sizeIsGreaterThanZero()) {
                app.listBitmap // trigger lazy init
            }
        }
    }

    /**
     * Pre-initialize listBitmap for newly created PackageInfoStruct instances
     * (from changeExport) before they become visible via editApplicationsBatch.
     */
    private fun preWarmEditBitmaps(edits: List<Pair<Int, PackageInfoStruct>>) {
        for ((_, newApp) in edits) {
            if (newApp.icon.sizeIsGreaterThanZero()) {
                newApp.listBitmap // trigger lazy init
            }
        }
    }

    private fun getAppFilterElements() {
        val map = mutableMapOf<IconPack, List<RawElement>>()

        installedApplications = appManager.getAllInstalledApplications()

        for (iconPack in iconPacks) {
            map[iconPack] = appManager.getAppFilterRawElements(iconPack.packageName, installedApplications)
        }

        iconPackAppFilterElement = map

        iconPackLoaded = true
    }

    suspend fun forceSync() {
        if (iconPackLoaded) {
            initializeIconPacks()
        }
    }

    private fun editApplication(oldApp: PackageInfoStruct, newApp: PackageInfoStruct) {
        val index = applicationList.indexOf(oldApp)
        if (index >= 0)
            editApplication(index, newApp)
    }

    fun editApplication(index: Int, newApp: PackageInfoStruct) {
        applicationList = applicationList.toMutableList().also {
            it[index] = newApp
        }
    }

    /**
     * Apply multiple edits at once, creating only a single new list.
     * Each pair maps the index in applicationList to the new PackageInfoStruct.
     */
    private fun editApplicationsBatch(edits: List<Pair<Int, PackageInfoStruct>>) {
        if (edits.isEmpty()) return
        val mutable = applicationList.toMutableList()
        for ((index, newApp) in edits) {
            mutable[index] = newApp
        }
        applicationList = mutable.toList()
    }

    fun copy(): ApplicationProvider {
        val newProvider = ApplicationProvider(context)

        newProvider.applicationList = applicationList.toList()
        newProvider.iconPacks = iconPacks.toList()
        newProvider.iconPackLoaded = iconPackLoaded
        newProvider.iconPackAppFilterElement = iconPackAppFilterElement.toMap()
        newProvider.installedApplications = installedApplications.toList()
        newProvider.calendarIcon = calendarIcon.toMap()
        newProvider.calendarIconsDrawable = calendarIconsDrawable.toMap()
        newProvider.defaultColor = defaultColor

        return newProvider
    }

    private fun getIconPackAppDrawables(iconPack: String): Map<InstalledApplication, ResourceDrawable> {
        if (iconPack == "") return emptyMap()
        val entry = iconPackAppFilterElement.entries.find { it.key.packageName == iconPack } ?: return emptyMap()

        val apps = entry.value

        return appManager.getDrawableFromAppFilterElements(
            iconPack,
            installedApplications,
            apps
        )
    }

    private fun getIconPackAppDrawable(app: InstalledApplication, iconPack: String): Map<InstalledApplication, ResourceDrawable> {
        if (iconPack == "") return emptyMap()
        val entry = iconPackAppFilterElement.entries.find { it.key.packageName == iconPack } ?: return emptyMap()

        val apps = entry.value

        return appManager.getDrawableFromAppFilterElements(
            iconPack,
            listOf(app),
            apps
        )
    }

    fun getIconPackIcons(iconPackName: String, options: GenerationOptions, drawables: List<ResourceDrawable>): Map<ResourceDrawable, ExportableIcon> {
        val exportDrawables = mutableMapOf<ResourceDrawable, ExportableIcon>()

        val pack = IconPackContainer("", emptyMap())

        val builder = IconGenerator(context, options, pack, pack)
        for (drawable in drawables) {
            exportDrawables[drawable] = builder.colorizeFromIconPack(iconPackName, drawable)
        }

        return exportDrawables
    }

    fun getIconPackDropdownIcons(application: InstalledApplication?): Map<String, ResourceDrawable> {
        val map = mutableMapOf<String, ResourceDrawable>()

        for (pack in iconPacks) {
            if (application == null) {
                val icon = appManager.getResIcon(pack.packageName, pack.iconID)

                if (icon != null) {
                    map[pack.packageName] = ResourceDrawable(pack.iconID, icon)
                }
            } else {
                val icons = getIconPackAppDrawable(application, pack.packageName)

                if (icons.isNotEmpty()) {
                    map[pack.packageName] = icons[application]!!
                }
            }
        }

        return map
    }

    data class BuiltIconPack(
        val uri: Uri,
        val packageName: String,
        val canBeInstalled: Boolean
    )
}