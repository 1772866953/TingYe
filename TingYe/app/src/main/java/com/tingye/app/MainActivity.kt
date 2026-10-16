@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.tingye.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewTreeObserver
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tingye.app.data.Book
import com.tingye.app.data.Chapter
import com.tingye.app.data.Bookmark
import com.tingye.app.data.ListeningSettings
import com.tingye.app.data.*
import com.tingye.app.playback.PlaybackService
import com.tingye.app.playback.TimerUi
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val model by viewModels<MainViewModel>()
    private var firstContentReady = false
    private var systemSplashVisible by mutableStateOf(true)
    private var notificationLaunch by mutableIntStateOf(0)
    private val playbackNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private var notificationPermissionAsked = false
    private val files = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri -> runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            model.importFiles(uris, if (uris.size == 1) DocumentFile.fromSingleUri(this, uris.first())?.name?.substringBeforeLast('.') ?: "新书" else "新导入的小说")
        }
    }
    private val folder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            model.importFolder(uri, DocumentFile.fromTreeUri(this, uri)?.name ?: "新导入的小说")
        }
    }
    private val batchFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            model.prepareBatchImport(uri, DocumentFile.fromTreeUri(this, uri)?.name ?: "新导入的小说")
        }
    }
    private val backupExport = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) model.exportBackup(uri)
    }
    private val backupImport = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.inspectBackup(uri)
    }
    private var coverTarget: Book? = null
    private val cover = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            coverTarget?.let { model.cover(it, uri) }
        }
    }
    private val relink = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            model.relinkMissing(uri)
        }
    }
    private val wallpaperPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            model.customWallpaper(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) systemSplashVisible = false
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            splashScreen.setOnExitAnimationListener { splash ->
                splash.animate().alpha(0f).setDuration(180).withEndAction {
                    splash.remove()
                    systemSplashVisible = false
                }.start()
            }
        }
        window.decorView.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!firstContentReady) return false
                window.decorView.viewTreeObserver.removeOnPreDrawListener(this)
                if (android.os.Build.VERSION.SDK_INT < 31) systemSplashVisible = false
                return true
            }
        })
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        if (intent?.action == PlaybackService.ACTION_OPEN_PLAYER) notificationLaunch++
        setContent {
            val libraryReady by model.libraryReady.collectAsStateWithLifecycle()
            val playback by model.playerUi.collectAsStateWithLifecycle()
            LaunchedEffect(playback.autoplayRequested, playback.isPlaying) {
                if ((playback.autoplayRequested || playback.isPlaying) &&
                    android.os.Build.VERSION.SDK_INT >= 33 && !notificationPermissionAsked &&
                    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    notificationPermissionAsked = true
                    playbackNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            val loadedSettings by produceState<ListeningSettings?>(initialValue = null) {
                model.preferences.values.collect { settings ->
                    if (settings.wallpaper == "custom") settings.wallpaperUri?.let {
                        preloadWallpaperImage(this@MainActivity, it)
                    }
                    value = settings
                }
            }
            val settings = loadedSettings
            LaunchedEffect(settings != null && libraryReady) {
                if (settings != null && libraryReady) {
                    // Keep the native starting window until the populated shelf is laid out.
                    withFrameNanos { }
                    firstContentReady = true
                }
            }
            if (settings != null) {
            val haptics = remember { AppHaptics(this@MainActivity) }
            DisposableEffect(haptics) { onDispose { haptics.close() } }
            SideEffect { haptics.strength = settings.hapticStrength }
            val dark = when (settings.theme) { "dark" -> true; "light" -> false; else -> androidx.compose.foundation.isSystemInDarkTheme() }
            val lightStatusBars = !dark && !systemSplashVisible
            SideEffect {
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = lightStatusBars
                    isAppearanceLightNavigationBars = lightStatusBars
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                    val appearance = (if (lightStatusBars) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0) or
                        (if (lightStatusBars) WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else 0)
                    window.insetsController?.setSystemBarsAppearance(appearance, mask)
                }
            }
            TingYeTheme(dark) {
                CompositionLocalProvider(LocalMotionSpeed provides settings.transitionSpeed,
                    LocalAppHaptics provides haptics, LocalRippleConfiguration provides null) {
                    Box(Modifier.fillMaxSize()) {
                        MainScreen(model, { files.launch(arrayOf("audio/*")) }, { folder.launch(null) },
                            { book -> coverTarget = book; cover.launch(arrayOf("image/*")) },
                            { relink.launch(arrayOf("audio/*")) },
                            { wallpaperPicker.launch(arrayOf("image/*")) },
                            { backupExport.launch("TingYe-backup.json") },
                            { backupImport.launch(arrayOf("application/json", "*/*")) },
                            notificationLaunch, settings, { batchFolder.launch(null) })
                    }
                }
            }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == PlaybackService.ACTION_OPEN_PLAYER) notificationLaunch++
    }
}

private val Ink = Color(0xFF243B34)
private val Sage = Color(0xFF47685B)
private val Paper = Color(0xFFF8F5F0)
private val Night = Color(0xFF181F1C)
private val coverColors = listOf(Color(0xFF587269), Color(0xFF876D61), Color(0xFF6C718A), Color(0xFF917C55))
private val LocalMotionSpeed = compositionLocalOf { 1f }
private val LocalAppHaptics = staticCompositionLocalOf<AppHaptics> { error("Haptics unavailable") }

@Composable private fun TingYeTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) darkColorScheme(primary = Color(0xFFA9C9B8), onPrimary = Color(0xFF173B30), primaryContainer = Color(0xFF345648), onPrimaryContainer = Color(0xFFE1F2E7), secondary = Color(0xFFACC7B8), onSecondary = Color(0xFF193B30), secondaryContainer = Color(0xFF345648), onSecondaryContainer = Color(0xFFE1F2E7), background = Night, surface = Color(0xFF26312B), onBackground = Color(0xFFE9EEE9), onSurface = Color(0xFFE9EEE9))
    else lightColorScheme(primary = Sage, onPrimary = Color.White, primaryContainer = Color(0xFFDDEBE2), onPrimaryContainer = Ink, secondary = Sage, onSecondary = Color.White, secondaryContainer = Color(0xFFE5ECE6), onSecondaryContainer = Ink, background = Paper, surface = Color.White, onBackground = Ink, onSurface = Ink)
    MaterialTheme(colorScheme = colors, typography = Typography()) {
        CompositionLocalProvider(LocalContentColor provides colors.onBackground, content = content)
    }
}

private enum class Page { SHELF, SETTINGS }
private data class OpenOrigin(val bookId: Long, val anchor: String?)
private data class SourceAnchors(val bookId: Long, val key: String, val container: Rect, val cover: Rect,
    val title: Rect?, val subtitle: Rect?, val viewport: Rect?)
private data class TargetAnchors(val cover: Rect, val title: Rect, val subtitle: Rect)

private fun LayoutCoordinates.sourceBoundsInRoot(): Rect =
    findRootCoordinates().localBoundingBoxOf(this, clipBounds = false)

/** One motion vocabulary: the spring owns interruption/velocity, bezier is for small fades. */
private object Motion {
    const val containerStiffness = 520f
    const val containerDamping = 1f
    // Float springs otherwise finish around 0.01 progress. On a full-screen
    // shared element that can leave a visible 10–20 px jump at the handoff.
    fun container(speed: Float) = spring<Float>(dampingRatio = containerDamping,
        stiffness = containerStiffness * speed * speed, visibilityThreshold = 0.0001f)
    fun press(speed: Float) = spring<Float>(dampingRatio = 0.9f, stiffness = 900f * speed * speed)
    fun duration(baseMs: Int, speed: Float) = (baseMs / speed).roundToInt().coerceAtLeast(1)
    val smallCurve = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    fun fade(from: Float, to: Float, progress: Float): Float {
        val fraction = ((progress - from) / (to - from)).coerceIn(0f, 1f)
        return fraction * fraction * (3f - 2f * fraction)
    }
}

@Composable private fun pressLayer(modifier: Modifier, interaction: MutableInteractionSource,
    pressedScale: Float = 0.91f, reboundDamping: Float = 0.72f): Modifier {
    val speed = LocalMotionSpeed.current
    if (pressedScale >= 0.98f) {
        val pressed by interaction.collectIsPressedAsState()
        val scale by animateFloatAsState(if (pressed) pressedScale else 1f,
            Motion.press(speed), label = "卡片按压")
        return modifier.graphicsLayer { scaleX = scale; scaleY = scale }
    }
    val scale = remember(interaction) { Animatable(1f) }
    LaunchedEffect(interaction, speed, pressedScale, reboundDamping) {
        var startedAt = 0L
        interaction.interactions.collectLatest { event ->
            when (event) {
                is PressInteraction.Press -> {
                    startedAt = android.os.SystemClock.uptimeMillis()
                    // A quick tap must have a visible contact frame, too.
                    scale.snapTo(minOf(scale.value, 0.95f))
                    scale.animateTo(pressedScale, tween(Motion.duration(45, speed)))
                }
                is PressInteraction.Release -> {
                    if (scale.value > pressedScale + 0.005f)
                        scale.animateTo(pressedScale, tween(Motion.duration(25, speed)))
                    val remaining = Motion.duration(85, speed) - (android.os.SystemClock.uptimeMillis() - startedAt)
                    if (remaining > 0) delay(remaining)
                    scale.animateTo(1f, spring(dampingRatio = reboundDamping, stiffness = 650f * speed * speed,
                        visibilityThreshold = 0.0001f))
                }
                is PressInteraction.Cancel -> scale.animateTo(1f, Motion.press(speed))
            }
        }
    }
    return modifier.graphicsLayer {
        scaleX = scale.value; scaleY = scale.value
    }
}

@Composable private fun MotionTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalAppHaptics.current
    TextButton(onClick = { haptics.click(onClick) }, modifier = pressLayer(modifier, interaction),
        enabled = enabled, interactionSource = interaction, content = content)
}

@Composable private fun MotionFilledTonalButton(onClick: () -> Unit, modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalAppHaptics.current
    FilledTonalButton(onClick = { haptics.click(onClick) }, modifier = pressLayer(modifier, interaction),
        interactionSource = interaction, content = content)
}

@Composable private fun MotionButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalAppHaptics.current
    Button(onClick = { haptics.click(onClick) }, enabled = enabled, modifier = pressLayer(modifier, interaction),
        interactionSource = interaction, content = content)
}

@Composable private fun MotionFilterChip(selected: Boolean, onClick: () -> Unit,
    label: @Composable () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalAppHaptics.current
    FilterChip(selected = selected, onClick = { haptics.click(onClick) }, label = label,
        modifier = pressLayer(modifier, interaction), interactionSource = interaction)
}

private fun blend(a: Float, b: Float, t: Float) = a + (b - a) * t

/** Keep the native track, keyboard and accessibility behavior, with immediate
 * horizontal dragging and continuous finger tracking even for discrete values. */
@Composable private fun AppSlider(value: Float, onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f, steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null) {
    val configuration = LocalViewConfiguration.current
    val directTouch = remember(configuration) {
        object : ViewConfiguration by configuration { override val touchSlop = 0f }
    }
    val interaction = remember { MutableInteractionSource() }
    val dragging by interaction.collectIsDraggedAsState()
    var draft by remember(valueRange) { mutableFloatStateOf(value) }
    SideEffect { if (!dragging) draft = value }
    fun snap(target: Float): Float = if (steps > 0) {
            val interval = (valueRange.endInclusive - valueRange.start) / (steps + 1)
            (valueRange.start + ((target - valueRange.start) / interval).roundToInt() * interval)
                .coerceIn(valueRange.start, valueRange.endInclusive)
        } else target.coerceIn(valueRange.start, valueRange.endInclusive)
    fun finish() {
        val committed = snap(draft)
        draft = committed
        onValueChange(committed)
        onValueChangeFinished?.invoke()
    }
    CompositionLocalProvider(LocalViewConfiguration provides directTouch) {
        Slider(value = draft, onValueChange = { draft = it; onValueChange(it) },
            modifier = modifier.semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(draft, valueRange, steps)
                setProgress { target ->
                    if (!enabled) false else { draft = target; finish(); true }
                }
            }.onPreviewKeyEvent { event ->
                if (!enabled || steps == 0) false else {
                    val interval = (valueRange.endInclusive - valueRange.start) / (steps + 1)
                    val target = when (event.key) {
                        Key.DirectionRight, Key.DirectionUp -> draft + interval
                        Key.DirectionLeft, Key.DirectionDown -> draft - interval
                        Key.MoveHome -> valueRange.start
                        Key.MoveEnd -> valueRange.endInclusive
                        else -> null
                    }
                    if (target == null) false else {
                        if (event.type == KeyEventType.KeyDown) { draft = target; finish() }
                        true
                    }
                }
            }, enabled = enabled, valueRange = valueRange, steps = 0,
            onValueChangeFinished = ::finish, interactionSource = interaction)
    }
}

@Composable private fun MotionIconButton(onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalAppHaptics.current
    IconButton(onClick = { haptics.click(onClick) }, modifier = pressLayer(modifier, interaction),
        enabled = enabled, interactionSource = interaction, content = content)
}

@Composable private fun MotionCheckbox(checked: Boolean, onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalAppHaptics.current
    Checkbox(checked, { checked -> haptics.click { onCheckedChange(checked) } }, enabled = enabled,
        modifier = pressLayer(Modifier, interaction), interactionSource = interaction)
}

@Composable private fun MotionMenuItem(text: @Composable () -> Unit, onClick: () -> Unit,
    modifier: Modifier = Modifier, leadingIcon: (@Composable () -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null) {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val haptics = LocalAppHaptics.current
    DropdownMenuItem(text = text, onClick = { haptics.click(onClick) }, leadingIcon = leadingIcon,
        modifier = pressLayer(modifier, interaction), interactionSource = interaction)
}

/** At the bottom, upward overscroll belongs to the content, not the sheet's spring. */
private val sheetEndBoundary = object : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (available.y < 0f) Offset(0f, available.y) else Offset.Zero
    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        if (available.y < 0f) Velocity(0f, available.y) else Velocity.Zero
}
private fun Modifier.sheetScroll(state: ScrollState): Modifier =
    nestedScroll(sheetEndBoundary).verticalScroll(state, overscrollEffect = null)

/** All submenus open fully and finish their exit animation before being removed. */
private typealias SheetDismiss = (() -> Unit) -> Unit
@Composable private fun AppSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.(dismiss: SheetDismiss) -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var dismissing by remember { mutableStateOf(false) }
    val dismiss: SheetDismiss = { after ->
        if (!dismissing) { dismissing = true; scope.launch { state.hide(); onDismiss(); after() } }
        Unit
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state,
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        scrimColor = Color.Black.copy(alpha = 0.28f)) {
        Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.86f).dp)
            .padding(bottom = 16.dp)) { content(dismiss) }
    }
}

private fun remainingLabel(value: RemainingListening): String {
    if (value.unknownChapters > 0) return "全书时长待补齐 · ${value.unknownChapters} 章时长未知"
    val minutes = (value.wallTimeMs + 59_999) / 60_000
    return if (minutes == 0L) "全书已听完" else "全书剩余约 " +
        if (minutes >= 60) "${minutes / 60} 小时 ${minutes % 60} 分钟" else "$minutes 分钟"
}
private fun blend(a: Rect, b: Rect, t: Float) = Rect(blend(a.left, b.left, t), blend(a.top, b.top, t), blend(a.right, b.right, t), blend(a.bottom, b.bottom, t))

@Stable
private class PlayerVisual(
    val progress: () -> Float,
    val source: () -> SourceAnchors?,
    val target: () -> TargetAnchors?
) {
    var titleStartScale by mutableFloatStateOf(1f)
    fun titleAlpha(): Float = if (source()?.title != null && target() != null)
        Motion.fade(0.68f, 0.84f, progress()) else 1f
    fun subtitleAlpha(): Float = if (source()?.subtitle != null && target() != null)
        Motion.fade(0.68f, 0.84f, progress()) else 1f
    fun titleTravel(): Offset = travel(source()?.title, target()?.title)
    fun subtitleTravel(): Offset = travel(source()?.subtitle, target()?.subtitle)
    private fun travel(start: Rect?, end: Rect?): Offset {
        if (start == null || end == null) return Offset.Zero
        val p = progress().coerceIn(0f, 1f)
        return Offset((start.left - end.left) * (1f - p), (start.top - end.top) * (1f - p))
    }
    fun titleScale(): Float = blend(titleStartScale, 1f, progress().coerceIn(0f, 1f))
    fun headerAlpha(): Float = if (source() == null) progress() else Motion.fade(0.46f, 0.73f, progress())
    fun bodyAlpha(): Float = if (source() == null) progress() else Motion.fade(0.41f, 0.81f, progress())
}

@Composable private fun MotionFrame(progress: () -> Float, content: @Composable (Float) -> Unit) {
    content(progress().coerceIn(0f, 1f))
}

@Composable private fun MovingText(
    sourceText: String, source: Rect, target: Rect, progress: Float,
    sourceStyle: androidx.compose.ui.text.TextStyle, targetStyle: androidx.compose.ui.text.TextStyle,
    sourceColor: Color, targetColor: Color, density: androidx.compose.ui.unit.Density,
    viewportWidth: Float, overlayAlpha: Float,
    targetText: String = sourceText, sourceMaxLines: Int = 1,
    measurer: TextMeasurer
) {
    // Geometry, typography and handoff windows are unchanged. Cache the two fixed
    // paragraphs; moving glyphs need drawing updates, not a new Text layout per frame.
    val sourceWidth = minOf(source.width, viewportWidth - source.left).coerceAtLeast(1f)
    val targetWidth = minOf(target.width, viewportWidth - target.left).coerceAtLeast(1f)
    val sourceLayout = remember(measurer, sourceText, sourceStyle, sourceWidth, sourceMaxLines) {
        measurer.measure(sourceText, sourceStyle, maxLines = sourceMaxLines,
            overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = sourceWidth.roundToInt()))
    }
    val targetLayout = remember(measurer, targetText, targetStyle, targetWidth) {
        measurer.measure(targetText, targetStyle, maxLines = 2,
            overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = targetWidth.roundToInt()))
    }
    Canvas(Modifier.fillMaxSize().clearAndSetSemantics { }) {
        val frame = blend(source, target, progress)
        val matching = sourceText == targetText
        val targetOpacity = if (matching) Motion.fade(0.12f, 0.34f, progress)
            else Motion.fade(0.30f, 0.86f, progress)
        val sourceOpacity = 1f - if (matching) Motion.fade(0.12f, 0.34f, progress)
            else Motion.fade(0.14f, 0.70f, progress)
        if (sourceOpacity > 0f) {
            val factor = if (matching)
                blend(1f, (targetStyle.fontSize.value / sourceStyle.fontSize.value).coerceIn(0.5f, 2f), progress)
                else 1f
            withTransform({ translate(frame.left, frame.top); scale(factor, factor, Offset.Zero) }) {
                drawText(sourceLayout, color = sourceColor.copy(alpha = sourceColor.alpha * overlayAlpha * sourceOpacity))
            }
        }
        if (targetOpacity > 0f) {
            val factor = if (matching)
                blend((sourceStyle.fontSize.value / targetStyle.fontSize.value).coerceIn(0.5f, 1.5f), 1f, progress)
                else 1f
            val color = if (matching) lerp(sourceColor, targetColor, progress) else targetColor
            withTransform({ translate(frame.left, frame.top); scale(factor, factor, Offset.Zero) }) {
                drawText(targetLayout, color = color.copy(alpha = color.alpha * overlayAlpha * targetOpacity))
            }
        }
    }
}

@Composable private fun MainScreen(vm: MainViewModel, pickFiles: () -> Unit, pickFolder: () -> Unit,
    pickCover: (Book) -> Unit, relink: () -> Unit, pickWallpaper: () -> Unit,
    exportBackup: () -> Unit, importBackup: () -> Unit,
    notificationLaunch: Int, settings: ListeningSettings, pickBatchFolder: () -> Unit) {
    val motionSpeed = LocalMotionSpeed.current
    val haptics = LocalAppHaptics.current
    val movingTextMeasurer = rememberTextMeasurer(cacheSize = 32)
    val books by vm.books.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val chapters by vm.chapters.collectAsStateWithLifecycle()
    val playbackChapters by vm.playbackChapters.collectAsStateWithLifecycle()
    val chapterCounts by vm.chapterCounts.collectAsStateWithLifecycle()
    val player by vm.playerUi.collectAsStateWithLifecycle()
    val timer by vm.timerUi.collectAsStateWithLifecycle()
    val progressUndo by vm.progressUndo.collectAsStateWithLifecycle()
    val importing by vm.importUi.collectAsStateWithLifecycle()
    val batchPreview by vm.batchPreview.collectAsStateWithLifecycle()
    val singlePreview by vm.singlePreview.collectAsStateWithLifecycle()
    val backupPreview by vm.backupPreview.collectAsStateWithLifecycle()
    val backupMessage by vm.backupMessage.collectAsStateWithLifecycle()
    val missingChapter by vm.missingChapter.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(Page.SHELF) }
    var settingsActive by remember { mutableStateOf(page == Page.SETTINGS) }
    val settingsMotion = remember { Animatable(if (page == Page.SETTINGS) 1f else 0f) }
    LaunchedEffect(page, motionSpeed) {
        if (page == Page.SETTINGS) settingsActive = true
        if (settingsActive) {
            settingsMotion.animateTo(if (page == Page.SETTINGS) 1f else 0f,
                Motion.container(motionSpeed))
            if (page == Page.SHELF) settingsActive = false
        }
    }
    var overlayActive by rememberSaveable { mutableStateOf(false) }
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    var playerRequest by remember { mutableIntStateOf(0) }
    var playerGestureActive by remember { mutableStateOf(false) }
    var chaptersOpen by rememberSaveable { mutableStateOf(false) }
    var importMenuOpen by rememberSaveable { mutableStateOf(false) }
    var pendingPlayId by remember { mutableStateOf<Long?>(null) }
    var origin by remember { mutableStateOf<OpenOrigin?>(null) }
    var activeSource by remember { mutableStateOf<SourceAnchors?>(null) }
    var activeTarget by remember { mutableStateOf<TargetAnchors?>(null) }
    var handledNotification by remember { mutableIntStateOf(0) }
    var targetCover by remember { mutableStateOf<Rect?>(null) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var dragProgress by remember { mutableFloatStateOf(Float.NaN) }
    val anchors = remember { mutableStateMapOf<String, Rect>() }
    val motion = remember { Animatable(if (playerOpen) 1f else 0f) }
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(timer.notice) { timer.notice?.let { snackbarHost.showSnackbar(it) } }
    var editing by remember { mutableStateOf<Book?>(null) }
    var deleting by remember { mutableStateOf<Book?>(null) }
    var bookSettings by remember { mutableStateOf<Book?>(null) }
    val book = books.firstOrNull { it.id == selected }
    val coverLift = animateFloatAsState(if (player.isPlaying && player.bookId == book?.id) 1f else 0f,
        spring(dampingRatio = 0.88f, stiffness = 210f * motionSpeed * motionSpeed,
            visibilityThreshold = 0.0001f), label = "封面浮起")
    val coverShadowColor = rememberCoverShadowColor(book?.title ?: "听页", book?.coverUri)
    val bookChapters = remember(chapters, book?.id) { if (book == null) emptyList() else chapters.filter { it.bookId == book.id } }
    val chapterListState = remember(book?.id) { LazyListState() }
    var chapterListVisited by remember(book?.id) { mutableStateOf(false) }
    LaunchedEffect(chaptersOpen, book?.id, bookChapters.size) {
        if (chaptersOpen && !chapterListVisited && bookChapters.isNotEmpty()) {
            val index = if (player.bookId == book?.id) player.index else
                bookChapters.indexOfFirst { it.id == book?.chapterId }.takeIf { it >= 0 } ?: book?.chapterIndex ?: 0
            chapterListState.scrollToItem(index.coerceIn(0, bookChapters.lastIndex))
            chapterListVisited = true
        }
    }
    // Reading the spring only inside MotionFrame/layer blocks keeps the entire
    // shelf and its LazyColumn from recomposing on every animation frame.
    val progress = { if (dragProgress.isNaN()) motion.value else dragProgress }
    fun sourceAt(id: Long, key: String?): SourceAnchors? {
        if (key == null) return null
        val container = anchors[key] ?: return null
        val cover = anchors["$key:cover"] ?: return null
        val viewport = if (key.startsWith("mini:")) null else anchors["shelf:viewport"]
        if (viewport != null && !container.overlaps(viewport)) return null
        return SourceAnchors(id, key, container, cover, anchors["$key:title"], anchors["$key:subtitle"], viewport)
    }
    fun refreshReturnSource() {
        val id = book?.id ?: return
        val original = origin?.anchor?.takeIf { origin?.bookId == id }
        activeSource = sourceAt(id, original) ?: sourceAt(id, "mini:$id")
    }
    val visibleSourceKey = activeSource?.key?.takeIf { activeSource?.bookId == book?.id }
    fun openPlayer(id: Long, anchor: String?, play: Boolean) {
        playerRequest++
        playerGestureActive = false
        dragProgress = Float.NaN
        if (anchor != null) haptics.selection()
        if (!overlayActive || motion.value < 0.15f) origin = OpenOrigin(id, anchor)
        activeSource = sourceAt(id, anchor)
        activeTarget = null
        if (book?.id != id) {
            targetCover = null
            anchors.remove("player:title")
            anchors.remove("player:subtitle")
        }
        vm.select(id)
        // Preparing Media3 can block a first-frame transition on slower devices.
        // Start the requested continuation after the shared surface settles.
        pendingPlayId = if (play) id else null
        chaptersOpen = false
        overlayActive = true
        playerOpen = true
    }
    fun closePlayer() {
        if (chaptersOpen) chaptersOpen = false else {
            haptics.selection()
            pendingPlayId = null
            refreshReturnSource()
            playerRequest++
            playerOpen = false
        }
    }
    LaunchedEffect(notificationLaunch, player.ready, player.bookId, selected) {
        if (notificationLaunch > handledNotification && player.ready) {
            val id = player.bookId ?: selected
            if (id != null) { handledNotification = notificationLaunch; openPlayer(id, null, false) }
        }
    }
    val measuredTitle = anchors["player:title"]
    val measuredSubtitle = anchors["player:subtitle"]
    LaunchedEffect(playerRequest, playerOpen, targetCover, measuredTitle, measuredSubtitle) {
        if (playerOpen && activeTarget == null) {
            val cover = targetCover
            if (cover != null && measuredTitle != null && measuredSubtitle != null)
                activeTarget = TargetAnchors(cover, measuredTitle, measuredSubtitle)
        }
    }
    LaunchedEffect(playerRequest, playerOpen, activeTarget != null, motionSpeed, playerGestureActive) {
        val request = playerRequest
        if (playerGestureActive) return@LaunchedEffect
        if (playerOpen) {
            if (activeTarget != null) {
                motion.animateTo(1f, Motion.container(motionSpeed))
                pendingPlayId?.takeIf { request == playerRequest && playerOpen && it == book?.id }?.let {
                    pendingPlayId = null
                    vm.playBook(it)
                }
            }
        } else if (overlayActive) {
            motion.animateTo(0f, Motion.container(motionSpeed))
            if (request == playerRequest && !playerOpen) {
                overlayActive = false; origin = null; activeSource = null; activeTarget = null
            }
        }
    }
    PredictiveBackHandler(enabled = overlayActive && !chaptersOpen) { events ->
        val request = ++playerRequest
        playerGestureActive = true
        try {
            refreshReturnSource()
            events.collect { event -> if (request == playerRequest) motion.snapTo(1f - event.progress) }
            if (request != playerRequest) return@PredictiveBackHandler
            pendingPlayId = null
            playerGestureActive = false
            playerRequest++
            playerOpen = false
            // The system owns predictive-back feedback; do not add a second pulse.
        } catch (cancelled: CancellationException) {
            if (request == playerRequest && playerOpen) { playerGestureActive = false; playerRequest++ }
        }
    }
    BackHandler(enabled = !overlayActive && settingsActive) { page = Page.SHELF }
    Box(Modifier.fillMaxSize().onGloballyPositioned { rootSize = it.size }) {
    WallpaperSurface(settings.wallpaper, settings.wallpaperUri, Modifier.fillMaxSize(),
        scrim = MaterialTheme.colorScheme.background, scrimAlpha = if (settings.theme == "dark") 0.72f else 0.78f,
        blurRadius = settings.wallpaperBlur)
    Scaffold(
        modifier = (if (overlayActive || page == Page.SETTINGS) Modifier.clearAndSetSemantics { } else Modifier)
            .graphicsLayer {
                alpha = if (overlayActive) 1f - Motion.fade(0.04f, 0.46f, progress()) else 1f
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            Row(Modifier.fillMaxWidth()
                .statusBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("听页", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    MotionIconButton(onClick = { importMenuOpen = false; settingsActive = true; page = Page.SETTINGS }) {
                        Icon(Icons.Default.Settings, "设置")
                    }
                    Box {
                        MotionIconButton(onClick = { importMenuOpen = true }) {
                            Icon(Icons.Default.MoreVert, "更多导入方式")
                        }
                        DropdownMenu(expanded = importMenuOpen, onDismissRequest = { importMenuOpen = false },
                            modifier = Modifier.clip(RoundedCornerShape(16.dp))) {
                            MotionMenuItem(text = { Text("选择音频") },
                                leadingIcon = { Icon(Icons.Default.AudioFile, null) },
                                onClick = { importMenuOpen = false; pickFiles() })
                            MotionMenuItem(text = { Text("批量导入多本书") },
                                leadingIcon = { Icon(Icons.Default.LibraryAdd, null) },
                                onClick = { importMenuOpen = false; pickBatchFolder() })
                            MotionMenuItem(text = { Text("选择文件夹") },
                                leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                                onClick = { importMenuOpen = false; pickFolder() })
                        }
                    }
            }
        },
        bottomBar = {
            if (player.bookId != null) {
                val playingBook = books.firstOrNull { it.id == player.bookId }
                val key = "mini:${player.bookId}"
                val miniInteraction = remember(key) { MutableInteractionSource() }
                DisposableEffect(key) { onDispose { listOf("", ":cover", ":title", ":subtitle", ":action").forEach { anchors.remove("$key$it") } } }
                Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(pressLayer(Modifier.fillMaxWidth(), miniInteraction, 0.985f)
                    .onGloballyPositioned { anchors[key] = it.boundsInRoot() }
                    .graphicsLayer { alpha = if (overlayActive && visibleSourceKey == key && progress() > 0f) 0f else 1f }
                    .clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface)
                    .clickable(interactionSource = miniInteraction, indication = null) { playingBook?.let { openPlayer(it.id, key, false) } }
                    .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    BookCover(playingBook?.title ?: "听页", 48, playingBook?.coverUri, Modifier.onGloballyPositioned { anchors["$key:cover"] = it.boundsInRoot() })
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(playingBook?.title ?: "正在收听", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.onGloballyPositioned { anchors["$key:title"] = it.boundsInRoot() })
                        Text(playbackChapters.getOrNull(player.index)?.title ?: "点击打开播放器", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.onGloballyPositioned { anchors["$key:subtitle"] = it.boundsInRoot() })
                    }
                    MotionIconButton(onClick = { haptics.action(); vm.toggle() }, modifier = Modifier.onGloballyPositioned {
                        anchors["$key:action"] = it.boundsInRoot()
                    }) { AnimatedPlayIcon(player.isPlaying) }
                }
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
                Shelf(books, player.bookId, chapterCounts, importing.busy, importing.found,
                    vm::cancelImport, anchors, if (overlayActive) visibleSourceKey else null, progress,
                    onOpen = { openPlayer(it.id, "book:${it.id}", false) },
                    onPlay = { openPlayer(it.id, "continue:${it.id}", true) },
                    onEdit = { bookSettings = it }, onDelete = { deleting = it }, interactive = !overlayActive && page != Page.SETTINGS)
        }
    }
    if (rootSize != IntSize.Zero) {
        SettingsSlideTransition(settingsMotion, rootSize, page == Page.SETTINGS, settingsActive,
            settings, vm, pickWallpaper, exportBackup, importBackup,
            onClose = { haptics.selection(); page = Page.SHELF })
    }
    // Keep the measured player composition for this Activity, without placing hidden nodes.
    // Hidden nodes cannot intercept touches or accessibility; reopening reuses the controls.
    val titleStartScale = when {
        visibleSourceKey?.startsWith("continue:") == true -> MaterialTheme.typography.titleLarge.fontSize.value
        visibleSourceKey?.startsWith("book:") == true -> MaterialTheme.typography.titleMedium.fontSize.value
        else -> MaterialTheme.typography.bodyLarge.fontSize.value
    } / MaterialTheme.typography.headlineSmall.fontSize.value
    val playerVisual = remember {
        PlayerVisual({ if (dragProgress.isNaN()) motion.value.coerceIn(0f, 1f) else dragProgress },
            { activeSource }, { activeTarget })
    }
    SideEffect { playerVisual.titleStartScale = titleStartScale.coerceIn(0.5f, 1.5f) }
    if (book != null && rootSize != IntSize.Zero) MotionFrame(progress) { p ->
        val sourceKey = visibleSourceKey
        val source = activeSource?.container
        // Source and overlay have identical measured bounds at zero. Showing
        // them together while they are different sizes produced a ghost card.
        val sourceFade = if (p > 0f) 1f else 0f
        // The spring already supplies the acceleration and settling. Remapping
        // its value here held the cover still and then made it race upward.
        val travel = p
        val full = Rect(0f, 0f, rootSize.width.toFloat(), rootSize.height.toFloat())
        val sharedClip = activeSource?.viewport?.let { blend(it, full, travel) } ?: full
        val frame = if (source != null) blend(source, full, travel) else full
        val coverStart = activeSource?.cover
        val coverEnd = activeTarget?.cover ?: targetCover ?: Rect(full.width / 2f - 90f, 110f, full.width / 2f + 90f, 290f)
        val coverFrame = if (coverStart != null) blend(coverStart, coverEnd, travel) else coverEnd
        val sourceTitle = activeSource?.title
        val sourceSubtitle = activeSource?.subtitle
        val targetTitle = activeTarget?.title ?: anchors["player:title"]
        val targetSubtitle = activeTarget?.subtitle ?: anchors["player:subtitle"]
        val titleHandoff = sourceTitle != null && targetTitle != null
        val subtitleHandoff = sourceSubtitle != null && targetSubtitle != null
        val density = LocalDensity.current
        // At rest the actual source owns the pixels. During travel the shared
        // surface is opaque, so returning to the source cannot dim for a frame.
        val overlayAlpha = if (source == null) p else sourceFade
        // The real player text owns the final travel, so no font-layer swap
        // remains in the last frames of opening or the first frames of return.
        val targetTextFade = Motion.fade(0.68f, 0.84f, p)
        val movingTextAlpha = overlayAlpha * (1f - targetTextFade)
        // The shelf heading has fully left before the player heading arrives.
        Box(Modifier.fillMaxSize().layout { measurable, constraints ->
            val child = measurable.measure(constraints)
            layout(child.width, child.height) { if (overlayActive) child.place(0, 0) }
        }.then(if (overlayActive) Modifier else Modifier.clearAndSetSemantics { }).drawWithContent {
            // Match the list's clipping at the source endpoint. A partially
            // scrolled card keeps its full shape and passes behind the same edge.
            clipRect(sharedClip.left, sharedClip.top, sharedClip.right, sharedClip.bottom) {
                this@drawWithContent.drawContent()
            }
        }.pointerInput(Unit) {
            awaitPointerEventScope { while (true) awaitPointerEvent() }
        }) {
            if (source != null) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.12f * p)))
            val originColor = when {
                sourceKey?.startsWith("continue:") == true -> MaterialTheme.colorScheme.primary
                source != null -> MaterialTheme.colorScheme.surface
                else -> MaterialTheme.colorScheme.background
            }
            val initialCornerDp = when {
                sourceKey?.startsWith("continue:") == true -> 24f
                sourceKey?.startsWith("book:") == true -> 18f
                sourceKey?.startsWith("mini:") == true -> 20f
                else -> 0f
            }
            val destinationColor = MaterialTheme.colorScheme.background
            // Draw in root pixels: non-uniform scaling flattened the vertical
            // radius and made a rounded card appear rectangular on return.
            Box(Modifier.fillMaxSize().drawBehind {
                val radius = with(density) { initialCornerDp.dp.toPx() } * (1f - travel)
                drawRoundRect(
                    color = lerp(originColor, destinationColor, travel).copy(alpha = overlayAlpha),
                    topLeft = Offset(frame.left, frame.top),
                    size = Size(frame.width, frame.height),
                    cornerRadius = CornerRadius(radius, radius)
                )
            })
            Box((if (chaptersOpen) Modifier.clearAndSetSemantics { } else Modifier).fillMaxSize()) {
                    PlayerSurface(book, bookChapters, player, settings.rewind, settings.forward, vm,
                        onBack = ::closePlayer, openChapters = { haptics.selection(); chaptersOpen = true }, pickCover = pickCover,
                        edit = { bookSettings = it }, onPlaybackAction = { haptics.action() }, onCover = { targetCover = it },
                        onTextLayout = { part, rect -> anchors["player:$part"] = rect },
                        visual = playerVisual,
                        drag = { value, finished, velocity ->
                            if (finished) {
                                val request = ++playerRequest
                                val close = velocity != Float.NEGATIVE_INFINITY && (value < 0.72f || velocity > 900f)
                                scope.launch {
                                    if (request != playerRequest) return@launch
                                    motion.snapTo(value.coerceIn(0f, 1f)); dragProgress = Float.NaN
                                    if (request != playerRequest) return@launch
                                    playerGestureActive = false
                                    if (close) { haptics.selection(); pendingPlayId = null; refreshReturnSource(); playerOpen = false }
                                    // The request effect is the only owner of settling motion.
                                    playerRequest++
                                }
                            } else { playerGestureActive = true; dragProgress = value.coerceIn(0f, 1f) }
                        }, heightPx = rootSize.height.toFloat())
            }
            if (titleHandoff) {
                MovingText(book.title, sourceTitle!!, targetTitle!!, travel,
                    when {
                        sourceKey?.startsWith("continue:") == true -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                        sourceKey?.startsWith("book:") == true -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                         else -> MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                    }, MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                    if (sourceKey?.startsWith("continue:") == true) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    MaterialTheme.colorScheme.onBackground, density, full.width, movingTextAlpha,
                    sourceMaxLines = if (sourceKey?.startsWith("book:") == true) 2 else 1, measurer = movingTextMeasurer)
            }
            if (subtitleHandoff) {
                val activeIndex = if (player.bookId == book.id) player.index else book.chapterIndex
                val chapterTitle = bookChapters.getOrNull(activeIndex)?.title ?: "等待播放"
                val sourceLabel = when {
                    sourceKey?.startsWith("continue:") == true -> progressLabel(book, chapterCounts[book.id])
                    sourceKey?.startsWith("book:") == true -> progressLabel(book, chapterCounts[book.id])
                    else -> playbackChapters.getOrNull(player.index)?.title ?: "点击打开播放器"
                }
                MovingText(sourceLabel, sourceSubtitle!!, targetSubtitle!!, travel,
                    when {
                        sourceKey?.startsWith("continue:") == true -> MaterialTheme.typography.labelLarge
                        sourceKey?.startsWith("book:") == true -> MaterialTheme.typography.bodyLarge
                        else -> MaterialTheme.typography.bodySmall
                    }, MaterialTheme.typography.titleMedium,
                    when {
                        sourceKey?.startsWith("continue:") == true -> MaterialTheme.colorScheme.onPrimary
                        sourceKey?.startsWith("book:") == true -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    MaterialTheme.colorScheme.primary, density, full.width, movingTextAlpha,
                    targetText = chapterTitle, sourceMaxLines = 2, measurer = movingTextMeasurer)
            }
            // The cover is the foreground plane when text paths briefly overlap.
            Box((if (chaptersOpen) Modifier.clearAndSetSemantics { } else Modifier)
                .size(with(density) { coverEnd.width.toDp() }, with(density) { coverEnd.height.toDp() })
                .graphicsLayer {
                    val atPlayer = Motion.fade(0.75f, 1f, p)
                    val lift = coverLift.value
                    val liftScale = 1f + 0.012f * lift * atPlayer
                    transformOrigin = TransformOrigin(0f, 0f)
                    translationX = coverFrame.left - coverFrame.width * (liftScale - 1f) / 2f
                    translationY = coverFrame.top + with(density) { (2f - 8f * lift).dp.toPx() } * atPlayer -
                        coverFrame.height * (liftScale - 1f) / 2f
                    scaleX = coverFrame.width / coverEnd.width.coerceAtLeast(1f) * liftScale
                    scaleY = coverFrame.height / coverEnd.height.coerceAtLeast(1f) * liftScale
                    shadowElevation = with(density) { (5f + 18f * lift).dp.toPx() } * atPlayer
                    shape = RoundedCornerShape(with(density) { (coverEnd.width / 5f).toDp() })
                    ambientShadowColor = coverShadowColor.copy(alpha = 0.34f)
                    spotShadowColor = coverShadowColor.copy(alpha = 0.70f)
                    clip = false
                    alpha = overlayAlpha
                }) {
                BookCover(book.title, null, book.coverUri, Modifier.fillMaxSize(), with(density) { (coverEnd.width / 5f).toDp() })
            }
            // The source card has a trailing control that is absent from the
            // shared cover/text plane. Draw it at its measured source position
            // until the card has moved away, so it does not pop in on return.
            val action = sourceKey?.let { anchors["$it:action"] }
            if (action != null &&
                (sourceKey.startsWith("mini:") || sourceKey.startsWith("book:"))) {
                Box(Modifier.size(with(density) { action.width.toDp() }, with(density) { action.height.toDp() })
                    .graphicsLayer {
                        translationX = action.left; translationY = action.top
                        alpha = overlayAlpha * (1f - Motion.fade(0f, 0.12f, p))
                    }, contentAlignment = Alignment.Center) {
                    if (sourceKey.startsWith("mini:"))
                        Icon(if (player.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            null, tint = MaterialTheme.colorScheme.onSurface)
                    else Icon(Icons.Default.MoreVert, null, tint = MaterialTheme.colorScheme.onSurface)
                }
            }
            // A second tap on the still visible closing container reverses the same Animatable.
            val closingSourceKey = sourceKey ?: origin?.anchor
            if (!playerOpen) Box(Modifier.offset { IntOffset(frame.left.roundToInt(), frame.top.roundToInt()) }
                .size(with(density) { frame.width.toDp() }, with(density) { frame.height.toDp() })
                .clickable { openPlayer(book.id, closingSourceKey, false) })
        }
    }
    // Independent transient overlay: it never changes shared player/source geometry.
    // Only the visible card takes input; removal also removes its touch/semantics nodes.
    AnimatedVisibility(visible = progressUndo != null && !settingsActive,
        modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp, start = 16.dp, end = 16.dp),
        enter = fadeIn(tween(160)) + slideInVertically(tween(220)) { -it / 3 },
        exit = fadeOut(tween(120))) {
        val displayed = remember { mutableStateOf(progressUndo) }
        progressUndo?.let { displayed.value = it }
        displayed.value?.let { restore ->
            Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 6.dp, shadowElevation = 4.dp) {
                Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("已更改收听位置", style = MaterialTheme.typography.labelLarge)
                        Text("${restore.point.chapter} · ${clock(restore.point.positionMs)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    MotionTextButton(enabled = progressUndo != null, onClick = { vm.undoProgress(restore.token) }) {
                        Text("返回刚才位置")
                    }
                }
            }
        }
    }
    }
    if (chaptersOpen && overlayActive && book != null) AppSheet(onDismiss = { chaptersOpen = false }) { dismiss ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp)) {
            Text("章节", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text("${book.title} · 共 ${bookChapters.size} 章", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val active = player.bookId == book.id
            val current = if (active) player.index else
                bookChapters.indexOfFirst { it.id == book.chapterId }.takeIf { it >= 0 } ?: book.chapterIndex
            Row(Modifier.padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.HourglassBottom, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(if (bookChapters.isEmpty()) "正在读取章节时长" else remainingLabel(remainingListening(
                    bookChapters, current, if (active) player.position else book.positionMs,
                    if (active) player.speed else book.speedOverride ?: settings.defaultSpeed,
                    book.introOverride ?: settings.intro, book.outroOverride ?: settings.outro,
                    complete = !active && book.status == 2)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        ChaptersPage(book.id, bookChapters,
            if (player.bookId == book.id) player.index else
                bookChapters.indexOfFirst { it.id == book.chapterId }.takeIf { it >= 0 } ?: book.chapterIndex,
            player.bookId == book.id, chapterListState,
            { i -> dismiss { vm.chooseChapter(book.id, bookChapters[i].id) } }, vm::moveChapter,
            { i -> dismiss { vm.restartChapter(book.id, bookChapters[i].id) } }, vm::markChapter)
    }
    batchPreview?.let { preview -> BatchImportSheet(preview, vm) }
    singlePreview?.let { preview -> SingleImportSheet(preview, vm) }
    importing.message?.let { message -> AlertDialog(onDismissRequest = vm::clearImportMessage, title = { Text("导入结果") }, text = { Text(message) }, confirmButton = { MotionTextButton(onClick = vm::clearImportMessage) { Text("知道了") } }) }
    player.error?.let { error -> AlertDialog(onDismissRequest = vm::clearError, title = { Text("播放提示") }, text = { Text(error) }, confirmButton = { if (missingChapter != null) MotionTextButton(onClick = { vm.clearError(); relink() }) { Text("重新定位") } else MotionTextButton(onClick = vm::clearError) { Text("知道了") } }, dismissButton = { if (missingChapter != null) MotionTextButton(onClick = vm::clearError) { Text("稍后") } }) }
    editing?.let { target ->
        var title by remember(target.id) { mutableStateOf(target.title) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("修改书名") }, text = { OutlinedTextField(title, { title = it }, singleLine = true, label = { Text("书名") }) }, confirmButton = { MotionTextButton(onClick = { vm.renameBook(target, title); editing = null }) { Text("保存") } }, dismissButton = { MotionTextButton(onClick = { editing = null }) { Text("取消") } })
    }
    deleting?.let { target -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("从书架移除？") }, text = { Text("只删除应用中的记录，不会删除原始音频文件。") }, confirmButton = { MotionTextButton(onClick = { vm.deleteBook(target.id); deleting = null }) { Text("移除") } }, dismissButton = { MotionTextButton(onClick = { deleting = null }) { Text("取消") } }) }
    bookSettings?.let { stale ->
        val current = books.firstOrNull { it.id == stale.id }
        if (current == null) bookSettings = null
        else BookSettingsSheet(current, settings, vm, onClose = { bookSettings = null },
            onRename = { bookSettings = null; editing = current },
            onCover = { bookSettings = null; pickCover(current) })
    }
    backupPreview?.let { preview ->
        var restoreSettings by remember(preview) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = vm::dismissBackup, title = { Text("预览本地备份") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("备份含 ${preview.books} 本书、${preview.chapters} 章、${preview.bookmarks} 个书签；与现有书名冲突 ${preview.conflicts} 本，将跳过，不覆盖。")
                Text("音频未包含在备份中；${preview.potentiallyNeedsAuthorization} 个章节 URI 的访问权限无法随备份转移，恢复后可能需要重新关联。")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MotionCheckbox(restoreSettings, { restoreSettings = it })
                    Text("同时恢复全局设置")
                }
            } },
            confirmButton = { MotionTextButton(onClick = { vm.confirmBackup(restoreSettings) }) { Text("确认导入") } },
            dismissButton = { MotionTextButton(onClick = vm::dismissBackup) { Text("取消") } })
    }
    backupMessage?.let { message -> AlertDialog(onDismissRequest = vm::dismissBackup,
        title = { Text("本地备份") }, text = { Text(message) },
        confirmButton = { MotionTextButton(onClick = vm::dismissBackup) { Text("知道了") } }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun BookSettingsSheet(book: Book, settings: ListeningSettings, vm: MainViewModel,
    onClose: () -> Unit, onRename: () -> Unit, onCover: () -> Unit) {
    AppSheet(onDismiss = onClose) { dismiss ->
        Column(Modifier.fillMaxWidth().height((LocalConfiguration.current.screenHeightDp * 0.78f).dp).sheetScroll(rememberScrollState())
            .padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("书籍设置 · ${book.title}", style = MaterialTheme.typography.titleLarge,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MotionTextButton(onClick = { dismiss(onRename) }) { Text("修改书名") }
                MotionTextButton(onClick = { dismiss(onCover) }) { Text("更换封面") }
            }
            val allChapters by vm.chaptersByBook.collectAsStateWithLifecycle()
            val player by vm.playerUi.collectAsStateWithLifecycle()
            val list = allChapters[book.id].orEmpty()
            val active = player.bookId == book.id
            Text(if (list.isEmpty()) "正在读取章节时长" else remainingLabel(remainingListening(list,
                if (active) player.index else list.indexOfFirst { it.id == book.chapterId }.takeIf { it >= 0 } ?: book.chapterIndex,
                if (active) player.position else book.positionMs,
                if (active) player.speed else book.speedOverride ?: settings.defaultSpeed,
                book.introOverride ?: settings.intro, book.outroOverride ?: settings.outro,
                complete = !active && book.status == 2)), minLines = 2, maxLines = 2,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary)
            HorizontalDivider()
            var bookSpeedDraft by remember(book.id, book.speedOverride) {
                mutableFloatStateOf(book.speedOverride ?: settings.defaultSpeed)
            }
            Text("倍速：${"%.2f".format(bookSpeedDraft)}×${if (book.speedOverride == null) "（全局默认）" else "（本书）"}")
            AppSlider(value = bookSpeedDraft, onValueChange = { bookSpeedDraft = it },
                onValueChangeFinished = { vm.setBookSpeed(book, (bookSpeedDraft * 20f).roundToInt() / 20f) },
                valueRange = 0.5f..3f)
            MotionTextButton(onClick = { vm.setBookSpeed(book, null) }) { Text("倍速恢复全局默认") }
            HorizontalDivider()
            Text("片头跳过：${book.introOverride?.let { "$it 秒（本书）" } ?: "全局 ${settings.intro} 秒"}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(0, 5, 10, 30).forEach { n ->
                MotionFilterChip(selected = book.introOverride == n,
                    onClick = { vm.setBookEdges(book, n, book.outroOverride) }, label = { Text("$n 秒") })
            } }
            Text("片尾跳过：${book.outroOverride?.let { "$it 秒（本书）" } ?: "全局 ${settings.outro} 秒"}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(0, 5, 10, 30).forEach { n ->
                MotionFilterChip(selected = book.outroOverride == n,
                    onClick = { vm.setBookEdges(book, book.introOverride, n) }, label = { Text("$n 秒") })
            } }
            MotionTextButton(onClick = { vm.setBookEdges(book, null, null) }) { Text("片头片尾恢复全局默认") }
        }
    }
}

private fun progressLabel(book: Book, count: Int?): String {
    val total = (count ?: 0).coerceAtLeast(1)
    val number = (book.chapterIndex + 1).coerceIn(1, total)
    val status = when (book.status) { 0 -> "未开始"; 2 -> "已听完"; else -> "继续收听" }
    return "$status · 第 $number / $total 章 · 本章 ${clock(book.positionMs)}"
}

@Composable private fun Shelf(books: List<Book>, playingBookId: Long?, counts: Map<Long, Int>,
    busy: Boolean, found: Int, cancel: () -> Unit, anchors: MutableMap<String, Rect>,
    hiddenKey: String?, progress: () -> Float, onOpen: (Book) -> Unit, onPlay: (Book) -> Unit,
    onEdit: (Book) -> Unit, onDelete: (Book) -> Unit, interactive: Boolean) {
    val motionSpeed = LocalMotionSpeed.current
    val listState = rememberLazyListState()
    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(ShelfFilter.ALL) }
    var sort by rememberSaveable { mutableStateOf(ShelfSort.RECENT) }
    var sorting by remember { mutableStateOf(false) }
    val visibleBooks = remember(books, query, filter, sort) { browseBooks(books, query, filter, sort) }
    DisposableEffect(Unit) { onDispose { anchors.remove("shelf:viewport") } }
    LazyColumn(Modifier.fillMaxSize().onGloballyPositioned { anchors["shelf:viewport"] = it.boundsInRoot() }, state = listState, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("让故事继续", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary) }
        val recent = books.firstOrNull { it.id == playingBookId }
            ?: books.firstOrNull { it.status != 0 }
        if (recent != null) item {
            val key = "continue:${recent.id}"
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val scale by animateFloatAsState(if (pressed) 0.985f else 1f, Motion.press(motionSpeed), label = "续听按压")
            DisposableEffect(key) { onDispose { listOf("", ":cover", ":title", ":subtitle", ":action").forEach { anchors.remove("$key$it") } } }
            Card(onClick = { onPlay(recent) }, modifier = Modifier.onGloballyPositioned { anchors[key] = it.sourceBoundsInRoot() }.graphicsLayer {
                scaleX = scale; scaleY = scale
                alpha = if (hiddenKey == key && progress() > 0f) 0f else 1f
            }, interactionSource = interaction, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary), shape = RoundedCornerShape(24.dp)) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    BookCover(recent.title, 52, recent.coverUri, Modifier.onGloballyPositioned { anchors["$key:cover"] = it.sourceBoundsInRoot() })
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(recent.title, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.onGloballyPositioned { anchors["$key:title"] = it.sourceBoundsInRoot() })
                        Text(progressLabel(recent, counts[recent.id]), color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.onGloballyPositioned { anchors["$key:subtitle"] = it.sourceBoundsInRoot() })
                    }
                }
            }
        }
        if (busy) item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)); Text("正在处理音频 · $found 章"); MotionTextButton(onClick = cancel) { Text("取消") } } }
        item {
            ShelfSearchHeader("我的书架 · ${visibleBooks.size}", query, { query = it }, searchOpen,
                { searchOpen = it; if (!it) query = "" }, interactive) {
                Box {
                    MotionTextButton(onClick = { sorting = true }) {
                        Icon(Icons.Default.Sort, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text(sort.label)
                    }
                    DropdownMenu(sorting, { sorting = false }) {
                        ShelfSort.entries.forEach { option ->
                            MotionMenuItem(text = { Text(option.label) }, onClick = { sort = option; sorting = false },
                                leadingIcon = { if (option == sort) Icon(Icons.Default.Check, null) })
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShelfFilter.entries.forEach { option ->
                    MotionFilterChip(selected = option == filter, onClick = { filter = option }, label = { Text(option.label) })
                }
            }
        }
        if (books.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.AutoMirrored.Filled.MenuBook, null, modifier = Modifier.size(58.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp)); Text("书架还是空的", style = MaterialTheme.typography.titleLarge)
                Text("点击右上角菜单，选择音频或文件夹", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (books.isNotEmpty() && visibleBooks.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.SearchOff, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp)); Text("没有符合条件的书")
                MotionTextButton(onClick = { query = ""; filter = ShelfFilter.ALL }) { Text("显示全部书籍") }
            }
        }
        itemsIndexed(visibleBooks, key = { _, item -> item.id }) { _, book ->
            Box(Modifier.animateItem()) { BookRow(book, counts[book.id], anchors, hiddenKey, progress, onOpen, onEdit, onDelete) }
        }
    }
}

@Composable private fun ShelfSearchHeader(title: String, query: String, onQuery: (String) -> Unit,
    expanded: Boolean, onExpanded: (Boolean) -> Unit, interactive: Boolean,
    sort: @Composable () -> Unit) {
    val speed = LocalMotionSpeed.current
    val progress by animateFloatAsState(if (expanded) 1f else 0f,
        spring(dampingRatio = 1f, stiffness = 520f * speed * speed, visibilityThreshold = 0.0001f),
        label = "书架搜索展开")
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val titleStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
    val measuredWidth = rememberTextMeasurer().measure(title, style = titleStyle).size.width
    var titleWidth by remember { mutableIntStateOf(measuredWidth) }
    fun close() { focusManager.clearFocus(); keyboard?.hide(); onExpanded(false) }
    BackHandler(enabled = interactive && expanded) { close() }
    LaunchedEffect(expanded, interactive) {
        if (expanded && interactive) { withFrameNanos { }; focus.requestFocus() }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().height(56.dp)) {
        Row((if (expanded) Modifier.clearAndSetSemantics { } else Modifier).fillMaxSize()
            .graphicsLayer { alpha = 1f - Motion.fade(0f, 0.28f, progress) },
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = titleStyle, modifier = Modifier.onSizeChanged { titleWidth = it.width })
            Spacer(Modifier.width(48.dp))
            Spacer(Modifier.weight(1f))
            sort()
        }
        val left = with(density) { titleWidth.toDp() }
        val fieldWidth = 48.dp + (maxWidth - 48.dp) * progress
        BasicTextField(query, onQuery, enabled = expanded, singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.width(fieldWidth).height(56.dp)
                .graphicsLayer { translationX = with(density) { left.toPx() } * (1f - progress) }
                .focusRequester(focus),
            decorationBox = { input ->
                Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = progress),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f * progress))) {
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        MotionIconButton(onClick = { if (expanded) close() else onExpanded(true) }) {
                            AnimatedContent(expanded, transitionSpec = {
                                fadeIn(tween(110)) togetherWith fadeOut(tween(80))
                            }, label = "搜索返回图标") { open ->
                                Icon(if (open) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Search,
                                    if (open) "收起书架搜索" else "搜索书架")
                            }
                        }
                        Box(Modifier.weight(1f).graphicsLayer { alpha = Motion.fade(0.35f, 0.8f, progress) }) {
                            if (query.isEmpty()) Text("搜索书名", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            input()
                        }
                        if (expanded && query.isNotEmpty()) MotionIconButton(onClick = { onQuery("") }) {
                            Icon(Icons.Default.Close, "清空搜索")
                        } else Spacer(Modifier.width(if (expanded) 12.dp else 0.dp))
                    }
                }
            })
    }
}

@Composable private fun SettingsSlideTransition(motion: Animatable<Float, *>, root: IntSize,
    open: Boolean, active: Boolean, settings: ListeningSettings, vm: MainViewModel, pickWallpaper: () -> Unit,
    exportBackup: () -> Unit, importBackup: () -> Unit, onClose: () -> Unit) {
    val density = LocalDensity.current
    val background = MaterialTheme.colorScheme.background
    val contentLayer = rememberGraphicsLayer()
    // Retain composition but unplace its interactive nodes on exit. Only the
    // recorded drawing is placed; the shelf remains live without a hidden hit target.
    val inputModifier = if (open) Modifier.pointerInput(Unit) {
        // Own the open page's background hit target without merging all child
        // slider semantics into a single full-screen clickable node.
        awaitPointerEventScope { while (true) awaitPointerEvent() }
    } else Modifier
    Box(Modifier.fillMaxSize().layout { measurable, constraints ->
            val child = measurable.measure(constraints)
            layout(child.width, child.height) { if (active) child.place(0, 0) }
        }.graphicsLayer {
            val p = motion.value.coerceIn(0f, 1f)
            // One transform for the complete opaque page in both directions.
            // A critically damped spring keeps velocity on quick reversals;
            // no scale, clipping, corner morph, or terminal fade is involved.
            translationX = root.width * (1f - p)
            // Fade the edge's depth with travel so no shadow remains after
            // the surface has left the screen.
            shadowElevation = with(density) { 8.dp.toPx() } * p
        }.then(inputModifier).drawBehind {
            drawRect(background)
        }) {
                Scaffold(containerColor = Color.Transparent,
                    modifier = Modifier.fillMaxSize().layout { measurable, constraints ->
                        val child = measurable.measure(constraints)
                        layout(child.width, child.height) { if (open) child.place(0, 0) }
                    }.then(if (open) Modifier else Modifier.clearAndSetSemantics { }).drawWithContent {
                        contentLayer.alpha = 1f
                        contentLayer.record { this@drawWithContent.drawContent() }
                        drawLayer(contentLayer)
                    }, topBar = {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        MotionIconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回书架") }
                        Text("设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    }
                }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).navigationBarsPadding()) {
                    SettingsPage(settings, vm, pickWallpaper, exportBackup, importBackup, open)
                }
                }
                if (!open && active) Canvas(Modifier.fillMaxSize()) { drawLayer(contentLayer) }
    }
}

private fun coverBaseColor(title: String) =
    coverColors[(kotlin.math.abs(title.hashCode().toLong()) % coverColors.size).toInt()]

@Composable private fun rememberCoverShadowColor(title: String, uri: String?): Color {
    val image = rememberImage(uri, 512)
    return remember(title, image) {
        val tint = runCatching {
            val bitmap = image?.asAndroidBitmap() ?: return@runCatching coverBaseColor(title)
            var red = 0f; var green = 0f; var blue = 0f; var weight = 0f
            for (x in 1..6) for (y in 1..6) {
                val color = Color(bitmap.getPixel((bitmap.width * x / 7).coerceAtMost(bitmap.width - 1),
                    (bitmap.height * y / 7).coerceAtMost(bitmap.height - 1)))
                val light = (color.red + color.green + color.blue) / 3f
                if (color.alpha < 0.5f || light !in 0.06f..0.92f) continue
                val w = (maxOf(color.red, color.green, color.blue) - minOf(color.red, color.green, color.blue)).coerceAtLeast(0.12f)
                red += color.red * w; green += color.green * w; blue += color.blue * w; weight += w
            }
            if (weight > 0f) Color(red / weight, green / weight, blue / weight) else coverBaseColor(title)
        }.getOrDefault(coverBaseColor(title))
        lerp(tint, Color(0xFF18201B), 0.62f)
    }
}

@Composable private fun BookRow(book: Book, count: Int?, anchors: MutableMap<String, Rect>, hiddenKey: String?, progress: () -> Float, onOpen: (Book) -> Unit, onEdit: (Book) -> Unit, onDelete: (Book) -> Unit) {
    val motionSpeed = LocalMotionSpeed.current
    var menu by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.985f else 1f, Motion.press(motionSpeed), label = "书籍按压")
    val key = "book:${book.id}"
    DisposableEffect(key) { onDispose { listOf("", ":cover", ":title", ":subtitle", ":action").forEach { anchors.remove("$key$it") } } }
    Card(onClick = { onOpen(book) }, modifier = Modifier.onGloballyPositioned { anchors[key] = it.sourceBoundsInRoot() }.graphicsLayer {
        scaleX = scale; scaleY = scale
        alpha = if (hiddenKey == key && progress() > 0f) 0f else 1f
    }, interactionSource = interaction, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCover(book.title, 68, book.coverUri, Modifier.onGloballyPositioned { anchors["$key:cover"] = it.sourceBoundsInRoot() })
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(book.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.onGloballyPositioned { anchors["$key:title"] = it.sourceBoundsInRoot() })
                Text(progressLabel(book, count), color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.onGloballyPositioned { anchors["$key:subtitle"] = it.sourceBoundsInRoot() })
            }
            Box {
                val editInteraction = remember { MutableInteractionSource() }
                val deleteInteraction = remember { MutableInteractionSource() }
                MotionIconButton(onClick = { menu = true }, modifier = Modifier.onGloballyPositioned {
                    anchors["$key:action"] = it.sourceBoundsInRoot()
                }) { Icon(Icons.Default.MoreVert, "更多操作") }
                DropdownMenu(menu, { menu = false }) {
                    MotionMenuItem(text = { Text("书籍设置") }, onClick = { menu = false; onEdit(book) },
                        modifier = Modifier, interactionSource = editInteraction)
                    MotionMenuItem(text = { Text("移除书籍") }, onClick = { menu = false; onDelete(book) },
                        modifier = Modifier, interactionSource = deleteInteraction)
                }
            }
        }
    }
}

@Composable private fun BookCover(title: String, size: Int?, uri: String? = null, modifier: Modifier = Modifier, corner: androidx.compose.ui.unit.Dp? = null) {
    val bitmap = rememberImage(uri, 512)
    val bg = coverBaseColor(title)
    val actualSize = size ?: 180
    Box(modifier.then(if (size == null) Modifier else Modifier.size(actualSize.dp)).clip(RoundedCornerShape(corner ?: (actualSize / 5).dp)).background(bg), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap, "封面", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.AutoMirrored.Filled.MenuBook, null, tint = Color.White, modifier = Modifier.fillMaxSize(0.5f))
    }
}

@Composable private fun AnimatedPlayIcon(playing: Boolean) {
    val motionSpeed = LocalMotionSpeed.current
    AnimatedContent(playing, transitionSpec = { fadeIn(tween(Motion.duration(130, motionSpeed), easing = Motion.smallCurve)) togetherWith fadeOut(tween(Motion.duration(100, motionSpeed))) }, label = "播放状态") { active ->
        Icon(if (active) Icons.Default.Pause else Icons.Default.PlayArrow, if (active) "暂停" else "播放")
    }
}

@Composable private fun PlayerSurface(book: Book, chapters: List<Chapter>, state: PlayerUi, rewind: Int, forward: Int, vm: MainViewModel,
    onBack: () -> Unit, openChapters: () -> Unit, pickCover: (Book) -> Unit, edit: (Book) -> Unit,
    onPlaybackAction: () -> Unit,
    onCover: (Rect) -> Unit, onTextLayout: (String, Rect) -> Unit, visual: PlayerVisual,
    drag: (Float, Boolean, Float) -> Unit, heightPx: Float) {
    var bookmarksPanel by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp)
            .pointerInput(heightPx) {
                var fraction = 1f
                var velocity = 0f
                var previousTime = 0L
                detectVerticalDragGestures(
                    onDragStart = { fraction = visual.progress(); velocity = 0f; previousTime = 0L },
                    onVerticalDrag = { change, amount ->
                        fraction = (fraction - amount / heightPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
                        val dt = (change.uptimeMillis - previousTime).coerceAtLeast(1L)
                        velocity = amount * 1000f / dt
                        previousTime = change.uptimeMillis
                        drag(fraction, false, velocity)
                        change.consume()
                    },
                    onDragEnd = { drag(fraction, true, velocity) },
                    onDragCancel = { drag(fraction, true, Float.NEGATIVE_INFINITY) }
                )
            }.graphicsLayer { alpha = visual.headerAlpha() }, verticalAlignment = Alignment.CenterVertically) {
            MotionIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "收起播放器") }
            Text("正在收听", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Box {
                var menuOpen by remember { mutableStateOf(false) }
                MotionIconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "更多操作") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    MotionMenuItem(text = { Text("书籍设置") }, onClick = { menuOpen = false; edit(book) })
                    MotionMenuItem(text = { Text("书签") }, onClick = { menuOpen = false; bookmarksPanel = true })
                }
            }
        }
        Box(Modifier.weight(1f).graphicsLayer { alpha = visual.bodyAlpha() }) {
            PlayerPage(book, chapters, state, rewind, forward, vm, openChapters, pickCover, edit, onPlaybackAction, onCover,
                bookmarksPanel, { bookmarksPanel = false },
                onTextLayout, visual)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PlayerPage(book: Book?, chapters: List<Chapter>, state: PlayerUi, rewind: Int, forward: Int, vm: MainViewModel, openChapters: () -> Unit, pickCover: (Book) -> Unit, edit: (Book) -> Unit, onPlaybackAction: () -> Unit, onCover: (Rect) -> Unit,
    bookmarksPanel: Boolean, closeBookmarks: () -> Unit,
    onTextLayout: (String, Rect) -> Unit, visual: PlayerVisual) {
    val motionSpeed = LocalMotionSpeed.current
    val haptics = LocalAppHaptics.current
    if (book == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("从书架选择一本小说") }; return }
    var seeking by remember { mutableStateOf<Float?>(null) }
    var skipDelta by remember { mutableIntStateOf(0) }
    var speedPanel by remember { mutableStateOf(false) }
    var timerPanel by remember { mutableStateOf(false) }
    var editingBookmark by remember { mutableStateOf<Bookmark?>(null) }
    var creatingBookmark by remember { mutableStateOf(false) }
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val timer by vm.timerUi.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val playInteraction = remember { MutableInteractionSource() }
    val index = if (state.bookId == book.id) state.index else
        chapters.indexOfFirst { it.id == book.chapterId }.takeIf { it >= 0 } ?: book.chapterIndex
    val duration = if (state.bookId == book.id) state.duration else 0
    val position = if (state.bookId == book.id) state.position else 0L
    val shownPosition = seeking?.let { (it * duration).toLong() } ?: position
    LaunchedEffect(skipDelta) { if (skipDelta != 0) { delay(650); skipDelta = 0 } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(8.dp))
        Box(Modifier.size(180.dp).onGloballyPositioned { onCover(it.boundsInRoot()) })
        Spacer(Modifier.height(24.dp))
        Text(book.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.onGloballyPositioned { onTextLayout("title", it.boundsInRoot()) }
                .graphicsLayer {
                    val travel = visual.titleTravel(); translationX = travel.x; translationY = travel.y
                    val scale = visual.titleScale(); scaleX = scale; scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0f); alpha = visual.titleAlpha()
                })
        Spacer(Modifier.height(4.dp))
        val captionTravel = with(LocalDensity.current) { 6.dp.roundToPx() }
        val captionDuration = Motion.duration(460, motionSpeed).coerceAtLeast(340)
        val captionCurve = CubicBezierEasing(0.22f, 0.61f, 0.36f, 1f)
        var previousCaptionIndex by remember(book.id) { mutableIntStateOf(index) }
        val captionDirection = remember(book.id, index) { if (index < previousCaptionIndex) -1 else 1 }
        SideEffect { previousCaptionIndex = index }
        AnimatedContent(Triple(index, chapters.getOrNull(index)?.title ?: "等待播放", captionDirection),
            contentAlignment = Alignment.TopCenter, transitionSpec = {
            // Use the last request's direction even if an earlier transition was interrupted.
            val direction = targetState.third
            // Briefly retire the old caption before revealing the new one. Small, directional
            // travel keeps rapid navigation readable; the centered layout avoids lateral drift.
            ((fadeIn(tween(Motion.duration(260, motionSpeed).coerceAtLeast(210),
                delayMillis = Motion.duration(120, motionSpeed).coerceAtLeast(90))) +
                slideInVertically(tween(captionDuration, easing = captionCurve)) { direction * captionTravel }) togetherWith
                (fadeOut(tween(Motion.duration(180, motionSpeed).coerceAtLeast(140))) +
                slideOutVertically(tween(Motion.duration(220, motionSpeed).coerceAtLeast(170), easing = captionCurve)) {
                    -direction * captionTravel
                })).using(SizeTransform(clip = false) { _, _ -> tween(captionDuration, easing = captionCurve) })
        }, label = "章节标题", modifier = Modifier.onGloballyPositioned { onTextLayout("subtitle", it.boundsInRoot()) }
            .graphicsLayer {
                val travel = visual.subtitleTravel(); translationX = travel.x; translationY = travel.y
                alpha = visual.subtitleAlpha()
            }) { caption ->
            Text(caption.second, style = MaterialTheme.typography.titleMedium, maxLines = 2,
                textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary)
        }
        // Reserve this line even when idle. Media3 preparing at the end of an
        // opening transition must not push the progress bar and controls down.
        Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
            if (state.loadingBookId == book.id || (state.bookId == book.id && state.buffering)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.autoplayRequested) "正在准备音频，完成后播放" else "正在准备音频，已取消自动播放",
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        AppSlider(value = seeking ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f, onValueChange = { seeking = it }, onValueChangeFinished = { seeking?.let { vm.seek((it * duration).toLong()) }; seeking = null }, enabled = duration > 0)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(clock(shownPosition), style = MaterialTheme.typography.bodySmall); Text("本章 · ${if (duration > 0) clock(duration) else "--:--"}", style = MaterialTheme.typography.bodySmall) }
        Box(Modifier.fillMaxWidth().height(30.dp), contentAlignment = Alignment.Center) {
            AnimatedContent(skipDelta, transitionSpec = { fadeIn(tween(Motion.duration(120, motionSpeed))) togetherWith fadeOut(tween(Motion.duration(170, motionSpeed))) }, label = "快进快退反馈") { delta ->
                if (delta != 0) Text(if (delta > 0) "+$delta 秒" else "$delta 秒", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            MotionIconButton(onClick = { onPlaybackAction(); vm.previous(book.id) }, enabled = index > 0,
                modifier = Modifier.size(48.dp)) { Icon(Icons.Default.SkipPrevious, "上一章") }
            MotionIconButton(onClick = { onPlaybackAction(); vm.skipBack(); skipDelta -= rewind }, modifier = Modifier.size(52.dp)) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.Replay, "快退 $rewind 秒", modifier = Modifier.size(24.dp)); Text("$rewind", style = MaterialTheme.typography.labelSmall) } }
            FilledIconButton(onClick = { onPlaybackAction(); if (state.loadingBookId == book.id || state.bookId == book.id) vm.toggle() else vm.playBook(book.id) }, modifier = pressLayer(Modifier.size(72.dp), playInteraction, pressedScale = 0.87f, reboundDamping = 0.58f), interactionSource = playInteraction) { AnimatedPlayIcon(state.isPlaying && state.bookId == book.id) }
            MotionIconButton(onClick = { onPlaybackAction(); vm.skipForward(); skipDelta += forward }, modifier = Modifier.size(52.dp)) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.Forward, "快进 $forward 秒", modifier = Modifier.size(24.dp)); Text("$forward", style = MaterialTheme.typography.labelSmall) } }
            MotionIconButton(onClick = { onPlaybackAction(); vm.next(book.id) }, enabled = index < chapters.lastIndex,
                modifier = Modifier.size(48.dp)) { Icon(Icons.Default.SkipNext, "下一章") }
        }
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            MotionTextButton(onClick = { speedPanel = true }) { Icon(Icons.Default.Speed, null); Spacer(Modifier.width(5.dp)); Text("${if (state.bookId == book.id) state.speed else book.speedOverride ?: settings.defaultSpeed}×") }
            MotionTextButton(onClick = { timerPanel = true }) { Icon(Icons.Default.Timer, null); Spacer(Modifier.width(5.dp)); Text(when {
                !timer.active || timer.bookId != book.id -> "定时停止"
                timer.mode == "chapter" -> "还听 ${timer.chaptersRemaining} 章"
                else -> "剩余 ${(timer.remainingMs + 59_999) / 60_000} 分"
            }) }
            MotionTextButton(onClick = openChapters) { Icon(Icons.AutoMirrored.Filled.List, null); Spacer(Modifier.width(5.dp)); Text("章节") }
        }
    }
    if (speedPanel) AppSheet(onDismiss = { speedPanel = false }) { _ ->
        val currentSpeed = if (state.bookId == book.id) state.speed else book.speedOverride ?: settings.defaultSpeed
        var speedDraft by remember(book.id) { mutableFloatStateOf(currentSpeed) }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("播放速度", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text("${"%.2f".format(speedDraft)}×", style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary)
            AppSlider(value = speedDraft, onValueChange = { speedDraft = it },
                onValueChangeFinished = { vm.speed((speedDraft * 20f).roundToInt() / 20f); haptics.selection() },
                valueRange = 0.5f..3f)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("0.5×", style = MaterialTheme.typography.bodySmall)
                Text("3×", style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf(1f, 1.25f, 1.5f, 2f).forEach { speed ->
                    MotionTextButton(onClick = { speedDraft = speed; vm.speed(speed) }) { Text("${speed}×") }
                }
            }
        }
    }
    if (timerPanel) SleepTimerSheet(book, state, timer, chapters.size, vm) { timerPanel = false }
    if (bookmarksPanel) AppSheet(onDismiss = closeBookmarks) { dismiss ->
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp)
            .sheetScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("书签 · ${book.title}", style = MaterialTheme.typography.titleLarge)
            MotionFilledTonalButton(onClick = { creatingBookmark = true }) { Text("在当前位置添加书签") }
            if (bookmarks.isEmpty()) Text("暂无书签")
            bookmarks.forEach { mark ->
                val title = chapters.firstOrNull { it.id == mark.chapterId }?.title ?: "章节需重新关联"
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("$title · ${clock(mark.positionMs)}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (mark.note.isNotBlank()) Text(mark.note, style = MaterialTheme.typography.bodySmall)
                        Row {
                            MotionTextButton(onClick = { dismiss { vm.jumpToBookmark(mark) } }) { Text("跳转") }
                            MotionTextButton(onClick = { editingBookmark = mark }) { Text("备注") }
                            MotionTextButton(onClick = { haptics.confirmation(); vm.deleteBookmark(mark) }) { Text("删除") }
                        }
                    }
                }
            }
        }
    }
    if (creatingBookmark) {
        var note by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { creatingBookmark = false }, title = { Text("添加书签") },
            text = { OutlinedTextField(note, { note = it }, label = { Text("备注（可选）") }, maxLines = 3) },
            confirmButton = { MotionTextButton(onClick = { haptics.confirmation(); vm.addBookmark(book.id, note); creatingBookmark = false }) { Text("保存") } },
            dismissButton = { MotionTextButton(onClick = { creatingBookmark = false }) { Text("取消") } })
    }
    editingBookmark?.let { mark ->
        var note by remember(mark.id) { mutableStateOf(mark.note) }
        AlertDialog(onDismissRequest = { editingBookmark = null }, title = { Text("修改书签备注") },
            text = { OutlinedTextField(note, { note = it }, maxLines = 3) },
            confirmButton = { MotionTextButton(onClick = { vm.editBookmark(mark, note); editingBookmark = null }) { Text("保存") } },
            dismissButton = { MotionTextButton(onClick = { editingBookmark = null }) { Text("取消") } })
    }
}

@Composable private fun ChaptersPage(bookId: Long, chapters: List<Chapter>, current: Int,
    activeBook: Boolean, listState: LazyListState, choose: (Int) -> Unit,
    move: (Chapter, Int) -> Unit, restart: (Int) -> Unit, mark: (Chapter, Boolean) -> Unit) {
    var query by rememberSaveable(bookId) { mutableStateOf("") }
    var jump by rememberSaveable(bookId) { mutableStateOf("") }
    var jumpOpen by remember { mutableStateOf(false) }
    var reorder by rememberSaveable(bookId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val filtered = remember(chapters, query) { chapters.mapIndexed { i, chapter -> i to chapter }.filter { (index, chapter) ->
        query.isBlank() || chapter.title.contains(query.trim(), true) || (index + 1).toString().contains(query.trim())
    } }
    Column(Modifier.fillMaxWidth().height((LocalConfiguration.current.screenHeightDp * 0.68f).dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), placeholder = { Text("搜索章节") },
                leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = RoundedCornerShape(18.dp))
            MotionIconButton(onClick = { jumpOpen = true }) { Icon(Icons.Default.FormatListNumbered, "按序号定位") }
            MotionIconButton(onClick = { reorder = !reorder }) {
                Icon(if (reorder) Icons.Default.Check else Icons.Default.SwapVert,
                    if (reorder) "完成调整顺序" else "调整章节顺序")
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (reorder) "使用箭头调整顺序" else "第 ${(current + 1).coerceAtMost(chapters.size)} / ${chapters.size} 章",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f))
            MotionTextButton(onClick = { scope.launch {
                query = ""; withFrameNanos { }
                if (chapters.isNotEmpty()) listState.animateScrollToItem(current.coerceIn(0, chapters.lastIndex))
            } }) { Text("定位当前") }
        }
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (filtered.isEmpty()) item { Text("没有匹配的章节", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            itemsIndexed(filtered, key = { _, pair -> pair.second.id }) { _, pair ->
                val (index, chapter) = pair
                val interaction = remember(chapter.id) { MutableInteractionSource() }
                val selected = index == current
                Card(onClick = { choose(index) }, modifier = pressLayer(Modifier.fillMaxWidth().animateItem(), interaction, 0.985f),
                    interactionSource = interaction, shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = if (selected) 0.16f else 0.07f)), contentAlignment = Alignment.Center) {
                            Text("${index + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(chapter.title, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val duration = chapter.durationMs?.takeIf { it > 0 }?.let { clock(it) }
                            Text(listOfNotNull(if (chapter.listened) "已听完" else if (chapter.positionMs > 0) "已听至 ${clock(chapter.positionMs)}" else "未听",
                                if (selected) (if (activeBook) "当前章节" else "上次收听") else null,
                                duration).joinToString(" · ").ifEmpty { "时长待识别" },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (reorder) {
                            MotionIconButton(onClick = { move(chapter, -1) }, enabled = index > 0,
                                modifier = Modifier.size(36.dp)) { Icon(Icons.Default.KeyboardArrowUp, "上移章节") }
                            MotionIconButton(onClick = { move(chapter, 1) }, enabled = index < chapters.lastIndex,
                                modifier = Modifier.size(36.dp)) { Icon(Icons.Default.KeyboardArrowDown, "下移章节") }
                        } else {
                            MotionIconButton(onClick = { mark(chapter, !chapter.listened) }) {
                                Icon(if (chapter.listened) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                    if (chapter.listened) "标为未听：${chapter.title}" else "标为已听完：${chapter.title}",
                                    tint = if (chapter.listened) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (selected) MotionIconButton(onClick = { restart(index) }) { Icon(Icons.Default.Replay, "重新播放本章") }
                        }
                    }
                }
            }
        }
    }
    if (jumpOpen) AlertDialog(onDismissRequest = { jumpOpen = false }, title = { Text("定位章节") },
        text = { OutlinedTextField(jump, { jump = it.filter(Char::isDigit) }, singleLine = true,
            label = { Text("章节序号 · 1–${chapters.size}") }, isError = jump.isNotEmpty() && jump.toIntOrNull() !in 1..chapters.size) },
        confirmButton = { MotionTextButton(onClick = {
            val target = jump.toIntOrNull()
            if (target != null && target in 1..chapters.size) { jumpOpen = false; scope.launch {
                query = ""; withFrameNanos { }; listState.animateScrollToItem(target - 1)
            } }
        }) { Text("定位") } }, dismissButton = { MotionTextButton(onClick = { jumpOpen = false }) { Text("取消") } })
}

@Composable private fun SleepTimerSheet(book: Book, state: PlayerUi, timer: TimerUi, totalChapters: Int,
    vm: MainViewModel, onDismiss: () -> Unit) {
    var chapterMode by remember { mutableStateOf(timer.active && timer.bookId == book.id && timer.mode == "chapter") }
    var minutes by remember { mutableFloatStateOf(if (timer.active && timer.bookId == book.id && timer.mode == "minutes")
        ((timer.remainingMs + 59_999) / 60_000).coerceIn(1, 180).toFloat() else 30f) }
    var chapterCount by remember { mutableFloatStateOf(if (timer.active && timer.bookId == book.id) timer.chaptersRemaining.coerceAtLeast(1).toFloat() else 1f) }
    var fade by remember { mutableIntStateOf(timer.fadeSeconds) }
    val compact = LocalConfiguration.current.screenHeightDp < 580
    val gap = if (compact) 2.dp else 8.dp
    val availableChapters = (totalChapters - if (state.bookId == book.id) state.index else book.chapterIndex).coerceIn(1, 20)
    LaunchedEffect(availableChapters) { chapterCount = chapterCount.coerceIn(1f, availableChapters.toFloat()) }
    AppSheet(onDismiss) { dismiss ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(gap)) {
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).sheetScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(gap)) {
            Text("睡眠定时", style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold)
            if (!compact || timer.active) Text(if (timer.active && timer.bookId == book.id) {
                if (timer.mode == "chapter") "已开启 · 再听 ${timer.chaptersRemaining} 章" else "已开启 · 剩余 ${(timer.remainingMs + 59_999) / 60_000} 分钟"
            } else "伴你入睡，到点轻轻停下", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MotionFilterChip(!chapterMode, { chapterMode = false }, { Text("按时间") }, Modifier.weight(1f))
                MotionFilterChip(chapterMode, { chapterMode = true }, { Text("按章节") }, Modifier.weight(1f))
            }
            // Keep both modes the same height so changing mode never shifts the sheet anchor.
            Box(Modifier.fillMaxWidth().height(140.dp)) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
            if (!chapterMode) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf(15, 30, 45, 60).forEach { value ->
                        MotionFilterChip(minutes.roundToInt() == value, { minutes = value.toFloat() }, { Text("$value 分") })
                    }
                }
                Text("${minutes.roundToInt()} 分钟后停止", style = MaterialTheme.typography.titleMedium)
                AppSlider(minutes, { minutes = it }, valueRange = 1f..180f,
                    onValueChangeFinished = { minutes = minutes.roundToInt().toFloat() })
            } else {
                Text(if (chapterCount.roundToInt() == 1) "本章结束后停止" else "再听 ${chapterCount.roundToInt()} 章后停止",
                    style = MaterialTheme.typography.titleMedium)
                if (availableChapters > 1) AppSlider(chapterCount, { chapterCount = it },
                    valueRange = 1f..availableChapters.toFloat(), steps = (availableChapters - 2).coerceAtLeast(0),
                    onValueChangeFinished = { chapterCount = chapterCount.roundToInt().toFloat() })
                Text("包含正在收听的这一章", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            }
            }
            HorizontalDivider()
            Text("停止前音量淡出", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(0, 10, 30).forEach { seconds ->
                    MotionFilterChip(fade == seconds, { fade = seconds }, { Text(if (seconds == 0) "关闭" else "$seconds 秒") })
                }
            }
            if (state.bookId != book.id) Text("播放本书后可以启用定时", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val activeTimer = timer.active && timer.bookId == book.id
            val applyTimer = { dismiss { vm.timer(if (chapterMode) 0 else minutes.roundToInt(),
                chapters = if (chapterMode) chapterCount.roundToInt() else 0, fadeSeconds = fade) } }
            if (compact && activeTimer) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MotionButton(onClick = applyTimer, enabled = state.bookId == book.id, modifier = Modifier.weight(1f)) { Text("更新定时") }
                if (timer.mode == "minutes") MotionTextButton(onClick = { dismiss { vm.extendTimer(15) } }) { Text("+15 分") }
                MotionTextButton(onClick = { dismiss { vm.timer(0) } }) { Text("取消") }
            } else MotionButton(onClick = applyTimer,
                enabled = state.bookId == book.id, modifier = Modifier.fillMaxWidth()) { Text(if (activeTimer) "更新定时" else "开启定时") }
            if (!compact && activeTimer) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (timer.mode == "minutes") MotionTextButton(onClick = { dismiss { vm.extendTimer(15) } }) { Text("延长 15 分钟") }
                MotionTextButton(onClick = { dismiss { vm.timer(0) } }) { Text("取消定时") }
            }
        }
    }
}

@Composable private fun SingleImportSheet(preview: SingleImportPreview, vm: MainViewModel) {
    var title by remember(preview) { mutableStateOf(preview.details.title) }
    var confirming by remember(preview) { mutableStateOf(false) }
    AppSheet({ if (!confirming) vm.discardSingleImport() }) { dismiss ->
        Column(Modifier.fillMaxWidth().sheetScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("导入预览", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BookCover(title, 80, preview.details.coverUri)
                Column(Modifier.weight(1f)) {
                    Text("${preview.files.size} 个音频文件", style = MaterialTheme.typography.titleMedium)
                    Text(if (preview.details.coverUri != null) "已找到封面" else "使用默认封面，可稍后更换",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedTextField(title, { title = it }, label = { Text("书名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("已优先读取音频书名和文件夹封面；没有信息时使用文件名。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (preview.failedDirectories > 0) Text("${preview.failedDirectories} 个目录未能读取", color = MaterialTheme.colorScheme.error)
            MotionButton(onClick = { confirming = true; dismiss { vm.confirmSingleImport(preview, title) } }, enabled = title.isNotBlank(),
                modifier = Modifier.fillMaxWidth()) { Text("导入书架") }
        }
    }
}

@Composable private fun BatchImportSheet(preview: BatchImportPreview, vm: MainViewModel) {
    var confirming by remember(preview) { mutableStateOf(false) }
    val selected = remember(preview) { mutableStateMapOf<String, Boolean>().apply {
        preview.groups.forEach { put(it.sourceTreeUri, it.freshCount > 0) }
    } }
    val titles = remember(preview) { mutableStateMapOf<String, String>().apply {
        preview.groups.forEach { put(it.sourceTreeUri, it.title) }
    } }
    val count = selected.values.count { it }
    AppSheet({ if (!confirming) vm.discardBatchImport() }) { dismiss ->
        Column(Modifier.fillMaxWidth().height((LocalConfiguration.current.screenHeightDp * 0.72f).dp)) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text("批量导入", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("每个子文件夹作为一本书 · 可修改书名", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (preview.failedDirectories > 0) Text("${preview.failedDirectories} 个文件夹读取失败", color = MaterialTheme.colorScheme.error)
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(preview.groups, key = { _, group -> group.sourceTreeUri }) { _, group ->
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            MotionCheckbox(selected[group.sourceTreeUri] == true,
                                { selected[group.sourceTreeUri] = it }, enabled = group.freshCount > 0)
                            BookCover(titles[group.sourceTreeUri].orEmpty(), 52, group.details.coverUri)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                OutlinedTextField(titles[group.sourceTreeUri].orEmpty(), { titles[group.sourceTreeUri] = it },
                                    label = { Text("书名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                Text("${group.freshCount} 章待导入 · ${group.duplicate} 章重复", Modifier.padding(top = 6.dp),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            MotionButton(onClick = { val chosen = titles.filterKeys { selected[it] == true }; confirming = true; dismiss { vm.confirmBatchImport(preview, chosen) } },
                enabled = count > 0, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) { Text("导入 $count 本书") }
        }
    }
}

@Composable private fun SettingsSection(title: String, summary: String, expanded: Boolean,
    onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val motionSpeed = LocalMotionSpeed.current
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalAppHaptics.current
    val arrow by animateFloatAsState(if (expanded) 180f else 0f,
        tween(Motion.duration(220, motionSpeed), easing = Motion.smallCurve), label = "设置分组箭头")
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column {
            Row(pressLayer(Modifier.fillMaxWidth(), interaction, 0.985f).clickable(interactionSource = interaction,
                indication = null, onClickLabel = if (expanded) "收起$title" else "展开$title",
                onClick = { onToggle(); haptics.selection() })
                .padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                Icon(Icons.Default.KeyboardArrowDown, null, Modifier.graphicsLayer { rotationZ = arrow })
            }
            AnimatedVisibility(expanded,
                enter = expandVertically(tween(Motion.duration(260, motionSpeed), easing = Motion.smallCurve)) +
                    fadeIn(tween(Motion.duration(190, motionSpeed))),
                exit = shrinkVertically(tween(Motion.duration(230, motionSpeed), easing = Motion.smallCurve)) +
                    fadeOut(tween(Motion.duration(160, motionSpeed)))) {
                Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
            }
        }
    }
}

@Composable private fun SettingsPage(settings: ListeningSettings, vm: MainViewModel,
    pickWallpaper: () -> Unit, exportBackup: () -> Unit, importBackup: () -> Unit, open: Boolean) {
    val haptics = LocalAppHaptics.current
    val context = LocalContext.current
    val days by vm.listeningDays.collectAsStateWithLifecycle()
    val completedBooks by vm.completedBooks.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf("") }
    val scroll = rememberScrollState()
    LaunchedEffect(open) { if (!open) { expanded = ""; scroll.scrollTo(0) } }
    var speedDraft by rememberSaveable { mutableFloatStateOf(settings.transitionSpeed) }
    var playbackSpeedDraft by rememberSaveable { mutableFloatStateOf(settings.defaultSpeed) }
    var undoSecondsDraft by rememberSaveable { mutableFloatStateOf(settings.progressUndoSeconds.toFloat()) }
    var blurDraft by rememberSaveable { mutableFloatStateOf(settings.wallpaperBlur) }
    var hapticDraft by rememberSaveable { mutableFloatStateOf(settings.hapticStrength.toFloat()) }
    LaunchedEffect(settings.transitionSpeed) { speedDraft = settings.transitionSpeed }
    LaunchedEffect(settings.defaultSpeed) { playbackSpeedDraft = settings.defaultSpeed }
    LaunchedEffect(settings.progressUndoSeconds) { undoSecondsDraft = settings.progressUndoSeconds.toFloat() }
    LaunchedEffect(settings.wallpaperBlur) { blurDraft = settings.wallpaperBlur }
    LaunchedEffect(settings.hapticStrength) { hapticDraft = settings.hapticStrength.toFloat() }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .verticalScroll(scroll).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsSection("外观与背景", "主题、书架壁纸、背景模糊", expanded == "appearance",
            { expanded = if (expanded == "appearance") "" else "appearance" }) {
            Text("显示主题", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (value, label) ->
                    MotionFilterChip(selected = settings.theme == value, onClick = { if (settings.theme != value) haptics.selection(); vm.theme(value) }, label = { Text(label) })
                }
            }
            HorizontalDivider()
            Text("书架壁纸", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                wallpaperChoices.forEach { choice ->
                    val interaction = remember(choice.id) { MutableInteractionSource() }
                    Card(onClick = { if (settings.wallpaper != choice.id) haptics.selection(); vm.wallpaper(choice.id) }, modifier = pressLayer(Modifier.weight(1f), interaction),
                        interactionSource = interaction,
                        border = if (settings.wallpaper == choice.id) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null) {
                        Box(Modifier.fillMaxWidth().height(88.dp)) {
                            WallpaperSurface(choice.id, null, Modifier.fillMaxSize(), scrimAlpha = 0.3f)
                            Text(choice.label, Modifier.align(Alignment.BottomStart).padding(8.dp), color = Color.White,
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            MotionFilterChip(selected = settings.wallpaper == "custom", onClick = pickWallpaper,
                label = { Text(if (settings.wallpaperUri == null) "选择本地图片" else "更换本地图片") })
            Box(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(16.dp))) {
                WallpaperSurface(settings.wallpaper, settings.wallpaperUri, Modifier.fillMaxSize(),
                    scrimAlpha = 0.18f, blurRadius = blurDraft)
                Text("背景预览", Modifier.align(Alignment.BottomStart).padding(12.dp), color = Color.White)
            }
            Text("壁纸模糊程度 ${blurDraft.roundToInt()}", style = MaterialTheme.typography.labelLarge)
            AppSlider(value = blurDraft, onValueChange = { blurDraft = it },
                onValueChangeFinished = { vm.wallpaperBlur(blurDraft.roundToInt().toFloat()) },
                valueRange = 0f..24f, steps = 23)
            Text("仅对自定义壁纸生效，不影响文字和控件。", style = MaterialTheme.typography.bodySmall)
        }
        SettingsSection("界面动效", "页面动画速度", expanded == "motion",
            { expanded = if (expanded == "motion") "" else "motion" }) {
            Text("页面过渡速度 ${"%.1f".format(speedDraft)}×", style = MaterialTheme.typography.labelLarge)
            AppSlider(value = speedDraft, onValueChange = { speedDraft = it },
                onValueChangeFinished = { vm.transitionSpeed((speedDraft * 10f).roundToInt() / 10f) },
                valueRange = 0.5f..2f, steps = 14)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("慢 0.5×", style = MaterialTheme.typography.bodySmall)
                MotionTextButton(onClick = { speedDraft = 1f; vm.transitionSpeed(1f) }) { Text("标准 1.0×") }
                Text("快 2.0×", style = MaterialTheme.typography.bodySmall)
            }
            Text("不影响音频播放速度。", style = MaterialTheme.typography.bodySmall)
        }
        SettingsSection("触觉反馈", "操作震动与强度", expanded == "haptics",
            { expanded = if (expanded == "haptics") "" else "haptics" }) {
            val level = hapticDraft.roundToInt().coerceIn(0, 3)
            val label = listOf("关闭", "轻", "标准", "强")[level]
            Text("震动强度 · $label", style = MaterialTheme.typography.labelLarge)
            AppSlider(value = hapticDraft, onValueChange = { hapticDraft = it },
                onValueChangeFinished = {
                    val selected = hapticDraft.roundToInt().coerceIn(0, 3)
                    hapticDraft = selected.toFloat()
                    vm.hapticStrength(selected)
                    haptics.preview(selected)
                }, valueRange = 0f..3f, steps = 2)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("关闭", style = MaterialTheme.typography.bodySmall)
                Text("轻", style = MaterialTheme.typography.bodySmall)
                Text("标准", style = MaterialTheme.typography.bodySmall)
                Text("强", style = MaterialTheme.typography.bodySmall)
            }
            Text("调整后预览震动。系统关闭触觉反馈时不生效。", style = MaterialTheme.typography.bodySmall)
        }
        SettingsSection("播放控制", "跳转时长、播放速度与进度恢复", expanded == "playback",
            { expanded = if (expanded == "playback") "" else "playback" }) {
            Text("音量均衡", style = MaterialTheme.typography.labelLarge)
            Text("自动调节录音音量，减小章节间的响度差异。",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MotionFilterChip(selected = !settings.volumeLeveling, onClick = { vm.volumeLeveling(false) }, label = { Text("关闭") })
                MotionFilterChip(selected = settings.volumeLeveling, onClick = { vm.volumeLeveling(true) }, label = { Text("开启") })
            }
            HorizontalDivider()
            Text("进度恢复提示 ${undoSecondsDraft.roundToInt()}s", style = MaterialTheme.typography.labelLarge)
            AppSlider(value = undoSecondsDraft, onValueChange = { undoSecondsDraft = it },
                onValueChangeFinished = {
                    undoSecondsDraft = undoSecondsDraft.roundToInt().coerceIn(1, 12).toFloat()
                    vm.progressUndoSeconds(undoSecondsDraft.toInt())
                    haptics.selection()
                }, valueRange = 1f..12f, steps = 10)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("1s", style = MaterialTheme.typography.bodySmall)
                Text("12s", style = MaterialTheme.typography.bodySmall)
            }
            Text("切换章节或跳转约 30 秒后，保留 ${undoSecondsDraft.roundToInt()}s 的进度恢复入口。",
                style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("默认倍速 ${"%.2f".format(playbackSpeedDraft)}×", style = MaterialTheme.typography.labelLarge)
            AppSlider(value = playbackSpeedDraft, onValueChange = { playbackSpeedDraft = it },
                onValueChangeFinished = { vm.defaultSpeed((playbackSpeedDraft * 20f).roundToInt() / 20f) },
                valueRange = 0.5f..3f)
            Text("书籍单独设置的播放速度和片头片尾时长优先。", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("快退 ${settings.rewind} 秒", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(10, 15, 30).forEach { n ->
                MotionFilterChip(selected = settings.rewind == n, onClick = { vm.skips(n, settings.forward) }, label = { Text("$n 秒") }) } }
            Text("快进 ${settings.forward} 秒", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(15, 30, 60).forEach { n ->
                MotionFilterChip(selected = settings.forward == n, onClick = { vm.skips(settings.rewind, n) }, label = { Text("$n 秒") }) } }
            HorizontalDivider()
            Text("恢复收听回退 ${settings.resumeBack} 秒", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(0, 3, 5).forEach { n ->
                MotionFilterChip(selected = settings.resumeBack == n, onClick = { vm.resumeBack(n) }, label = { Text("$n 秒") }) } }
            HorizontalDivider()
            Text("跳过片头 ${settings.intro} 秒", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(0, 5, 10, 30).forEach { n ->
                MotionFilterChip(selected = settings.intro == n, onClick = { vm.skipEdges(n, settings.outro) }, label = { Text("$n 秒") }) } }
            Text("跳过片尾 ${settings.outro} 秒", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(0, 5, 10, 30).forEach { n ->
                MotionFilterChip(selected = settings.outro == n, onClick = { vm.skipEdges(settings.intro, n) }, label = { Text("$n 秒") }) } }
        }
        SettingsSection("耳机控制", "上一首与下一首按键功能", expanded == "headphones",
            { expanded = if (expanded == "headphones") "" else "headphones" }) {
            listOf(true to "耳机上一首", false to "耳机下一首").forEach { (previous, title) ->
                Text(title, style = MaterialTheme.typography.labelLarge)
                val selected = if (previous) settings.headphonePrevious else settings.headphoneNext
                val choices = listOf("chapter" to if (previous) "上一章" else "下一章",
                    "rewind" to "快退 ${settings.rewind} 秒", "forward" to "快进 ${settings.forward} 秒", "none" to "不操作")
                Column {
                    choices.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { (value, label) -> MotionFilterChip(selected == value,
                                onClick = { vm.headphoneAction(previous, value) }, label = { Text(label) }) }
                        }
                    }
                }
            }
            Text("适用于耳机媒体按键，不影响播放／暂停键。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SettingsSection("桌面小组件", "桌面播放控制", expanded == "widget",
            { expanded = if (expanded == "widget") "" else "widget" }) {
            Text("显示书籍、章节及播放控制。也可长按桌面，选择“听页”小组件。",
                style = MaterialTheme.typography.bodySmall)
            val manager = android.appwidget.AppWidgetManager.getInstance(context)
            if (manager.isRequestPinAppWidgetSupported) MotionFilledTonalButton(onClick = {
                manager.requestPinAppWidget(android.content.ComponentName(context, com.tingye.app.widget.TingYeWidget::class.java), null, null)
            }) { Text("添加到桌面") }
        }
        SettingsSection("收听统计", "每日、每周与累计时长", expanded == "statistics",
            { expanded = if (expanded == "statistics") "" else "statistics" }) {
            val stats = listeningSummary(days)
            listOf("今天" to stats.todayMs, "本周" to stats.weekMs, "累计" to stats.totalMs).forEach { (label, time) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(listeningDurationLabel(time), fontWeight = FontWeight.SemiBold)
                }
            }
            HorizontalDivider()
            Text("已听完 $completedBooks 本", style = MaterialTheme.typography.bodyMedium)
            Text("按实际播放时间统计，不计入暂停、缓冲及跳过的时长。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SettingsSection("后台播放", "暂停保留与系统后台权限", expanded == "background",
            { expanded = if (expanded == "background") "" else "background" }) {
            Text("暂停后保留播放服务和控制入口；系统回收服务后，可通过耳机播放键恢复上次进度。",
                style = MaterialTheme.typography.bodySmall)
            Text("暂停时释放播放唤醒锁，停止进度轮询。长时间未操作时由系统回收，不持续唤醒设备。",
                style = MaterialTheme.typography.bodySmall)
            Text("OriginOS 需允许后台运行。系统强制停止或清理可能移除播放控件，重新打开听页可继续收听。",
                style = MaterialTheme.typography.bodySmall)
            MotionFilledTonalButton(onClick = {
                context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:${context.packageName}")))
            }) { Text("应用系统设置") }
        }
        SettingsSection("播放通知", "通知权限与锁屏显示", expanded == "notifications",
            { expanded = if (expanded == "notifications") "" else "notifications" }) {
            Text("管理播放通知权限和锁屏显示。锁屏控件由系统提供。", style = MaterialTheme.typography.bodySmall)
            MotionFilledTonalButton(onClick = {
                context.startActivity(Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, PlaybackService.PLAYBACK_CHANNEL_ID))
            }) { Text("通知设置") }
        }
        SettingsSection("本地备份", "书架、播放进度与应用设置", expanded == "backup",
            { expanded = if (expanded == "backup") "" else "backup" }) {
            Text("备份包含书架、进度、已听标记、书签、统计、设置和封面，不含音频文件。导入前可查看冲突，已有同名书籍不会被覆盖。",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MotionFilledTonalButton(onClick = exportBackup) { Text("导出备份") }
                MotionFilledTonalButton(onClick = importBackup) { Text("导入备份") }
            }
        }
        SettingsSection("数据与隐私", "本地存储、文件权限与数据备份", expanded == "privacy",
            { expanded = if (expanded == "privacy") "" else "privacy" }) {
            Text("数据存储", style = MaterialTheme.typography.labelLarge)
            Text("书架、播放进度、书签、已听标记、收听统计和应用设置仅保存在本机。听页不上传这些数据，不含广告或行为分析服务。",
                style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("文件访问", style = MaterialTheme.typography.labelLarge)
            Text("仅访问你选择并授权的音频、封面和备份文件。移除书籍只删除应用内记录，不删除原始音频。文件移动或权限失效后，需要重新选择文件。",
                style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("系统权限", style = MaterialTheme.typography.labelLarge)
            Text("通知权限用于播放控件，震动权限用于触觉反馈，播放唤醒锁用于熄屏收听。暂停或停止播放时释放唤醒锁。权限可在系统设置中管理。",
                style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("备份与删除", style = MaterialTheme.typography.labelLarge)
            Text("备份由你手动导出到指定位置，不含音频文件。卸载或清除应用数据会删除本机记录；操作前请导出备份，恢复后可能需要重新授权音频。",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun clock(ms: Long): String {
    val seconds = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0))
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%02d:%02d".format(seconds / 60, seconds % 60)
}
