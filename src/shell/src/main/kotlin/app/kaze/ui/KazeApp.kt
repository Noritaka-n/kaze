package app.kaze.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.Dialog
import app.kaze.data.CoverSource
import app.kaze.data.Game
import app.kaze.data.isAvailable
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import app.kaze.data.LibraryStore
import app.kaze.offline.OfflineText
import app.kaze.offline.OfflineTranslator
import app.kaze.offline.ScanResult
import app.kaze.offline.TextEntry
import app.kaze.offline.TextKey
import app.kaze.translate.SettingsStore
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private var lastTranslateGameId: String = ""

private val NavItems = listOf(
    Triple("资料库", "管理游戏与封面", Icons.Outlined.Home),
    Triple("翻译", "扫描、翻译与回填", Icons.Outlined.Translate),
    Triple("修改器", "运行时开关与变量", Icons.Outlined.Tune),
    Triple("设置", "连接、外观与安全", Icons.Outlined.Settings),
)

@Composable
fun KazeApp(window: ComposeWindow, dark: Boolean, onDarkChange: (Boolean) -> Unit) {
    var page by remember { mutableStateOf(0) }
    var selectedGame by remember { mutableStateOf<Game?>(null) }
    var showOfflineNotice by remember { mutableStateOf(true) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            TitleBar(window)
            Row(Modifier.fillMaxSize()) {
                Sidebar(page = page, onSelect = { page = it; if (it != 0) selectedGame = null })
                AnimatedContent(
                    targetState = page,
                    transitionSpec = {
                        val direction = if (targetState > initialState) 1 else -1
                        (fadeIn(tween(210)) + slideInHorizontally(tween(260)) { it / 14 * direction })
                            .togetherWith(
                                fadeOut(tween(150)) + slideOutHorizontally(tween(220)) { -it / 16 * direction },
                            )
                    },
                    label = "page-transition",
                    modifier = Modifier.fillMaxHeight().weight(1f),
                ) { current ->
                    when (current) {
                        0 -> AnimatedContent(
                            targetState = selectedGame?.id,
                            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                            label = "library-detail",
                        ) { selectedId ->
                            val game = selectedGame?.takeIf { it.id == selectedId }
                            if (game == null) LibraryPage(window, onOpenGame = { selectedGame = it })
                            else GameDetailPage(game, onBack = { selectedGame = null })
                        }
                        1 -> TranslatePage(window)
                        2 -> ModifierPage()
                        else -> SettingsPage(dark, onDarkChange)
                    }
                }
            }
        }
    }
    if (showOfflineNotice) {
        OfflineNoticeDialog { showOfflineNotice = false }
    }
}

@Composable
private fun OfflineNoticeDialog(onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.width(430.dp).padding(24.dp)) {
                Text("仅支持单机、离线游戏", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(10.dp))
                Text(
                    "本软件只支持单机、离线游戏。请勿用于联机游戏、网游或在线服务。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = onClose, shape = RoundedCornerShape(12.dp)) {
                        Text("知道了")
                    }
                }
            }
        }
    }
}

@Composable
private fun TitleBar(window: ComposeWindow) {
    var windowOrigin by remember { mutableStateOf<java.awt.Point?>(null) }
    var cursorOrigin by remember { mutableStateOf<java.awt.Point?>(null) }
    Row(
        Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).fillMaxHeight().pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        windowOrigin = window.location
                        cursorOrigin = java.awt.MouseInfo.getPointerInfo()?.location
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val start = windowOrigin ?: return@detectDragGestures
                        val from = cursorOrigin ?: return@detectDragGestures
                        val cursor = java.awt.MouseInfo.getPointerInfo()?.location ?: return@detectDragGestures
                        val screen = java.awt.Toolkit.getDefaultToolkit().screenSize
                        val nextX = (start.x + cursor.x - from.x)
                            .coerceIn(0, (screen.width - window.width).coerceAtLeast(0))
                        val nextY = (start.y + cursor.y - from.y)
                            .coerceIn(0, (screen.height - window.height).coerceAtLeast(0))
                        window.setLocation(nextX, nextY)
                    },
                )
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text("K", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Text("Kaze", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(10.dp))
            Text(
                "runtime text workbench",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.width(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            WindowButton(Icons.Outlined.Remove, "最小化") { window.extendedState = Frame.ICONIFIED }
            WindowButton(Icons.Outlined.CropSquare, "最大化") {
                window.extendedState = if (window.extendedState == Frame.MAXIMIZED_BOTH) Frame.NORMAL else Frame.MAXIMIZED_BOTH
            }
            WindowButton(Icons.Outlined.Close, "关闭", danger = true) {
                window.dispatchEvent(java.awt.event.WindowEvent(window, java.awt.event.WindowEvent.WINDOW_CLOSING))
            }
        }
    }
}


@Composable
private fun WindowButton(icon: ImageVector, description: String, danger: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        danger && hovered -> Color(0xFFE5484D)
        hovered -> MaterialTheme.colorScheme.surfaceVariant
        else -> Color.Transparent
    }
    val tint = if (danger && hovered) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(background)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size(15.dp), tint = tint)
    }
}

@Composable
private fun Sidebar(page: Int, onSelect: (Int) -> Unit) {
    Column(
        Modifier.width(226.dp).fillMaxHeight().padding(start = 14.dp, top = 8.dp, bottom = 16.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            tonalElevation = 1.dp,
            shadowElevation = 1.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(10.dp)) {
                NavItems.forEachIndexed { index, item ->
                    val selected = page == index
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable { onSelect(index) }
                            .padding(horizontal = 13.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            item.third,
                            null,
                            tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(19.dp),
                        )
                        Spacer(Modifier.width(11.dp))
                        Text(
                            item.first,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PageHeader(title: String, subtitle: String, action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action()
    }
    Spacer(Modifier.height(22.dp))
}

@Composable
private fun LibraryPage(window: ComposeWindow, onOpenGame: (Game) -> Unit) {
    var query by remember { mutableStateOf("") }
    var games by remember { mutableStateOf(LibraryStore.games) }
    var addNotice by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val availableGames = games.filter { it.isAvailable() }
    val filtered = availableGames.filter {
        query.isBlank() ||
            it.title.contains(query, true) ||
            it.subtitle.contains(query, true) ||
            it.engine.label.contains(query, true) ||
            it.path.contains(query, true)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 34.dp, vertical = 22.dp)) {
        PageHeader("资料库", "管理游戏、封面与启动入口") {
            Button(
                enabled = !scanning,
                onClick = {
                    val chooser = JFileChooser().apply {
                        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                        isMultiSelectionEnabled = false
                        isAcceptAllFileFilterUsed = false
                        dialogTitle = "选择游戏文件夹"
                    }
                    if (chooser.showOpenDialog(window) == JFileChooser.APPROVE_OPTION) {
                        val folder = chooser.selectedFile ?: return@Button
                        scanning = true
                        addNotice = "正在扫描：${folder.name}…"
                        scope.launch {
                            try {
                                val summary = withContext(Dispatchers.IO) {
                                    LibraryStore.scanFolder(folder).getOrThrow()
                                }
                                games = LibraryStore.games
                                addNotice = if (summary.added.isEmpty() && summary.alreadyKnown == 0) {
                                    "没有找到支持的 RPG Maker 游戏"
                                } else buildString {
                                    append("已添加 ${summary.added.size} 个游戏")
                                    if (summary.alreadyKnown > 0) append(" · 已在库中 ${summary.alreadyKnown}")
                                    if (summary.failed > 0) append(" · 未识别 ${summary.failed}")
                                    if (summary.truncated) append(" · 扫描达到上限，可能还有目录未处理")
                                }
                            } catch (error: Throwable) {
                                addNotice = "添加失败：${error.message ?: "未知错误"}"
                            } finally {
                                scanning = false
                            }
                        }
                    }
                },
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
            ) {
                Icon(Icons.Outlined.FolderOpen, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (scanning) "扫描中…" else "添加文件夹")
            }
        }
        addNotice?.let { notice ->
            Spacer(Modifier.height(8.dp))
            Text(
                notice,
                style = MaterialTheme.typography.bodyMedium,
                color = if (notice.startsWith("添加失败")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("搜索游戏、引擎或路径") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "${filtered.size} 个可用游戏 · ${filtered.map { it.engine.family }.distinct().size} 个引擎",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        if (filtered.isEmpty()) {
            EmptyLibrary()
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(250.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                contentPadding = PaddingValues(bottom = 28.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(filtered, key = { it.id }) { game -> GameCard(game, onOpen = { onOpenGame(game) }) }
            }
        }
    }
}

@Composable
private fun GameCover(
    game: Game,
    modifier: Modifier = Modifier,
    monogramSize: androidx.compose.ui.unit.TextUnit,
    fullImage: Boolean = false,
) {
    val cover by produceState<ImageBitmap?>(initialValue = null, game.cover) {
        value = withContext(Dispatchers.IO) {
            if (game.cover.isBlank()) null else runCatching {
                val file = File(game.cover)
                if (!file.isFile) return@runCatching null
                CoverSource.bytes(file)?.let { SkiaImage.makeFromEncoded(it).toComposeImageBitmap() }
            }.getOrNull()
        }
    }
    val image = cover
    if (image != null) {
        if (fullImage) {
            Image(
                image,
                contentDescription = game.title,
                modifier = modifier.fillMaxWidth().aspectRatio(image.width.toFloat() / image.height),
                contentScale = ContentScale.Fit,
            )
        } else {
            Image(image, contentDescription = game.title, modifier = modifier, contentScale = ContentScale.Crop)
        }
    } else {
        Box(
            modifier.then(if (fullImage) Modifier.height(260.dp) else Modifier)
                .background(Brush.linearGradient(listOf(Color(0xFF6B8E80), Color(0xFF3B6559)))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                game.monogram.ifBlank { "K" },
                color = Color.White.copy(alpha = 0.9f),
                fontSize = monogramSize,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun GameCard(game: Game, onOpen: () -> Unit) {
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
        modifier = Modifier.shadow(5.dp, RoundedCornerShape(24.dp), ambientColor = Color(0x0C000000))
            .fillMaxWidth(),
    ) {
        Box(Modifier.fillMaxWidth().height(160.dp)) {
            GameCover(game, Modifier.fillMaxSize(), 46.sp)
            Box(
                Modifier.fillMaxWidth().height(60.dp).align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.28f)))),
            )
            Surface(
                color = Color.Black.copy(alpha = 0.28f),
                shape = RoundedCornerShape(9.dp),
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
            ) {
                Text(
                    game.engine.label,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                )
            }
        }
        Column(Modifier.padding(16.dp)) {
            Text(game.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(
                game.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmptyLibrary() {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(38.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.FolderOpen, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
            Spacer(Modifier.height(12.dp))
            Text("还没有游戏", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(5.dp))
            Text(
                "添加游戏文件夹后，文本扫描和翻译会从这里开始。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GameDetailPage(game: Game, onBack: () -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var launchStatus by remember(game.id) { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 34.dp, vertical = 18.dp)) {
        TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
            Icon(Icons.Outlined.ArrowBack, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("资料库")
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(game.title, style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    game.subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Text(
                    game.engine.label,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp))) {
            GameCover(game, Modifier.fillMaxWidth(), 76.sp, fullImage = true)
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    launchStatus = "正在启动…"
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { app.kaze.data.Runtime.launchGame(game) }
                        launchStatus = result.fold(
                            onSuccess = { "启动成功：$it" },
                            onFailure = { "启动失败：${it.message ?: "未知错误"}" },
                        )
                    }
                },
                shape = RoundedCornerShape(13.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text("启动游戏")
            }
        }
        if (launchStatus.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                launchStatus,
                style = MaterialTheme.typography.bodyMedium,
                color = if (launchStatus.startsWith("启动失败")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(18.dp))
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                DetailRow("安装路径", game.path.ifBlank { "未记录" })
                DetailRow("启动程序", game.launcher.ifBlank { "自动检测" })
                DetailRow("原文语言", game.sourceLang.ifBlank { "自动" })
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(3.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TranslatePage(window: ComposeWindow) {
    val games = LibraryStore.games.filter { it.isAvailable() }
    var selectedGameId by remember {
        mutableStateOf(
            lastTranslateGameId.takeIf { id -> games.any { it.id == id } }
                ?: games.firstOrNull()?.id.orEmpty(),
        )
    }
    val game = games.firstOrNull { it.id == selectedGameId } ?: games.firstOrNull()
    val gameId = game?.id.orEmpty()
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var workJob by remember { mutableStateOf<Job?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ScanResult?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var translating by remember { mutableStateOf(false) }
    var translations by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var status by remember { mutableStateOf("") }
    var progressDone by remember { mutableStateOf(0) }
    var progressTotal by remember { mutableStateOf(0) }
    var liveMessage by remember { mutableStateOf("") }
    var showErrors by remember { mutableStateOf(false) }
    var retranslatingKey by remember { mutableStateOf("") }

    LaunchedEffect(gameId) {
        workJob?.cancel()
        workJob = null
        result = null
        translations = emptyMap()
        scanning = false
        translating = false
        retranslatingKey = ""
        showErrors = false
        progressDone = 0
        progressTotal = 0
        liveMessage = ""
        lastTranslateGameId = gameId
        status = if (game == null) "资料库里没有可用的 RPG Maker 游戏" else ""
    }

    fun scan() {
        val targetGame = game ?: return
        if (scanning) return
        val targetId = targetGame.id
        scanning = true
        status = "扫描游戏文本…"
        translations = emptyMap()
        workJob = scope.launch {
            try {
                val value = withContext(Dispatchers.IO) {
                    runCatching { OfflineText.scan(File(targetGame.path)) }.getOrNull()
                }
                if (targetId != gameId) return@launch
                result = value?.result
                status = when {
                    value == null -> "扫描失败"
                    value.result.entries.isEmpty() -> "没有找到可翻译文本"
                    else -> "扫描完成：${value.result.entries.size} 条，唯一文本 ${value.result.uniqueTexts} 条"
                }
            } finally {
                if (targetId == gameId) scanning = false
            }
        }
    }

    fun translate() {
        val snapshot = result ?: return
        val targetGame = game ?: return
        if (translating || snapshot.entries.isEmpty()) return
        val targetId = targetGame.id
        translating = true
        status = "准备翻译…"
        progressDone = 0
        progressTotal = 0
        workJob = scope.launch {
            try {
                OfflineTranslator.reset()
                val table = OfflineTranslator.run(snapshot.entries, targetGame.id)
                if (targetId != gameId) return@launch
                translations = table
                if (table.isEmpty()) {
                    status = OfflineTranslator.lastError
                        ?: OfflineTranslator.message.ifBlank { "没有产生译文" }
                    return@launch
                }
                status = "已翻译 ${table.size} 条，正在写回游戏…"
                if (SettingsStore.current.readOnly) {
                    status = "已翻译 ${table.size} 条 · 只读模式未写入游戏文件"
                } else {
                    val report = withContext(Dispatchers.IO) {
                        OfflineText.apply(File(targetGame.path), table)
                    }
                    status = buildString {
                        append("已翻译 ${table.size} 条 · 写入 ${report.replaced} 条 / ${report.files} 个文件")
                        if (report.skipped.isNotEmpty()) append(" · ${report.skipped.size} 个文件跳过")
                    }
                }
            } catch (error: Throwable) {
                status = "翻译失败：${error.message ?: error.javaClass.simpleName}"
            } finally {
                translating = false
            }
        }
    }

    fun retranslate(entry: TextEntry) {
        val targetGame = game ?: return
        if (translating || retranslatingKey.isNotEmpty()) return
        val targetId = targetGame.id
        val key = TextKey.of(entry.text)
        retranslatingKey = key
        status = "正在重新翻译：${entry.text.take(30)}…"
        workJob = scope.launch {
            try {
                val table = OfflineTranslator.run(
                    listOf(entry), targetGame.id, ignoreMemory = true,
                )
                if (targetId != gameId) return@launch
                val target = table[entry.text]
                if (target.isNullOrBlank()) {
                    status = OfflineTranslator.lastError ?: "重新翻译没有返回结果"
                    return@launch
                }
                translations = translations + (entry.text to target)
                if (SettingsStore.current.readOnly) {
                    status = "已重新翻译 · 只读模式未写入"
                } else {
                    OfflineText.apply(File(targetGame.path), mapOf(entry.text to target))
                    status = "已重新翻译并写入：${entry.text.take(30)}…"
                }
            } catch (error: Throwable) {
                status = "重新翻译失败：${error.message ?: error.javaClass.simpleName}"
            } finally {
                retranslatingKey = ""
            }
        }
    }

    fun exportTranslations() {
        val snapshot = result ?: return
        if (translations.isEmpty()) return
        val dialog = FileDialog(window, "导出译文", FileDialog.SAVE).apply {
            file = "translations.json"
            isVisible = true
        }
        val name = dialog.file ?: return
        val target = File(dialog.directory ?: game?.path ?: ".", name)
        val root = com.google.gson.JsonObject()
        root.addProperty("format", "kaze-mvtext")
        root.addProperty("version", 1)
        root.add("entries", com.google.gson.JsonArray().apply {
            snapshot.entries.forEach { entry ->
                add(com.google.gson.JsonObject().apply {
                    addProperty("locator", entry.locator)
                    addProperty("kind", entry.kind)
                    addProperty("source", entry.text)
                    addProperty("target", translations[entry.text] ?: "")
                })
            }
        })
        target.writeText(
            com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root),
            Charsets.UTF_8,
        )
        status = "译文已导出：${target.absolutePath}"
    }

    LaunchedEffect(translating) {
        while (translating) {
            progressDone = OfflineTranslator.done
            progressTotal = OfflineTranslator.total
            liveMessage = OfflineTranslator.message
            delay(350)
        }
        progressDone = OfflineTranslator.done
        progressTotal = OfflineTranslator.total
    }

    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 34.dp, vertical = 22.dp)) {
        PageHeader("翻译", "扫描游戏文本，使用你自己的大模型 API") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    AssistChip(
                        onClick = { pickerOpen = true },
                        enabled = games.isNotEmpty(),
                        modifier = Modifier.width(220.dp),
                        label = {
                            Text(
                                game?.title ?: "选择游戏",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, null, Modifier.size(18.dp)) },
                    )
                    DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                        games.forEach { candidate ->
                            DropdownMenuItem(
                                text = {
                                    Text("${candidate.title}  ·  ${candidate.engine.label}")
                                },
                                onClick = {
                                    selectedGameId = candidate.id
                                    lastTranslateGameId = candidate.id
                                    pickerOpen = false
                                },
                            )
                        }
                    }
                }
                AssistChip(
                    onClick = {},
                    label = { Text("API") },
                    leadingIcon = { Icon(Icons.Outlined.Storage, null, Modifier.size(16.dp)) },
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(game?.monogram ?: "K", color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(game?.title ?: "未选择游戏", style = MaterialTheme.typography.titleLarge)
                    Text(
                        game?.path ?: "先在资料库选择一款游戏",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Button(onClick = { scan() }, shape = RoundedCornerShape(13.dp), enabled = game != null && !scanning && !translating) {
                    Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(if (scanning) "扫描中…" else "扫描文本")
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StatCard(result?.entries?.size?.toString() ?: "—", "扫描条目", Modifier.weight(1f))
            StatCard(result?.uniqueTexts?.toString() ?: "—", "唯一文本", Modifier.weight(1f))
            StatCard(translations.size.toString(), "已翻译", Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(22.dp)) {
                Text("翻译队列", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        translating && progressTotal > 0 -> "翻译中 ${progressDone}/${progressTotal}"
                        translating -> "正在连接翻译服务…"
                        status.isNotBlank() -> status
                        result == null -> "先扫描游戏文本。"
                        else -> "可以开始翻译；译文会先查记忆库，再交给 Kaze。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { translate() },
                        shape = RoundedCornerShape(13.dp),
                        enabled = result != null && result!!.entries.isNotEmpty() && game != null && !translating && !scanning,
                    ) {
                        Text(if (translating) "翻译中…" else "开始翻译")
                    }
                    TextButton(
                        onClick = { showErrors = true },
                        enabled = translations.isNotEmpty() && !translating,
                    ) { Text("翻译错误") }
                    TextButton(
                        onClick = { exportTranslations() },
                        enabled = translations.isNotEmpty(),
                    ) { Text("导出译文") }
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (translations.isEmpty()) "" else "已生成 ${translations.size} 条译文",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
    if (showErrors && result != null) {
        TranslationErrorDialog(
            entries = result!!.entries,
            translations = translations,
            retranslatingKey = retranslatingKey,
            onRetranslate = { retranslate(it) },
            onClose = { showErrors = false },
        )
    }
}

@Composable
private fun TranslationErrorDialog(
    entries: List<TextEntry>,
    translations: Map<String, String>,
    retranslatingKey: String,
    onRetranslate: (TextEntry) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val shown = entries.filter { entry ->
        translations.containsKey(entry.text) &&
            (query.isBlank() || entry.text.contains(query, true) || translations[entry.text]?.contains(query, true) == true)
    }
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.width(820.dp).heightIn(max = 640.dp).padding(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("翻译错误", style = MaterialTheme.typography.headlineSmall)
                        Text("选择一条重新翻译，会同时更新服务端共享译文", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onClose) { Text("关闭") }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    placeholder = { Text("搜索原文或译文") },
                    singleLine = true, shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f)) {
                    items(shown, key = { it.locator + it.text }) { entry ->
                        val key = TextKey.of(entry.text)
                        Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(entry.text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(3.dp))
                                Text(translations[entry.text].orEmpty(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.width(12.dp))
                            TextButton(
                                onClick = { onRetranslate(entry) },
                                enabled = retranslatingKey.isBlank(),
                            ) { Text(if (retranslatingKey == key) "翻译中…" else "重新翻译") }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(18.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsPage(dark: Boolean, onDarkChange: (Boolean) -> Unit) {
    val settings = remember { SettingsStore.current }
    var baseUrl by remember { mutableStateOf(settings.baseUrl) }
    var model by remember { mutableStateOf(settings.model) }
    var apiKey by remember { mutableStateOf("") }
    var readOnly by remember { mutableStateOf(settings.readOnly) }
    var message by remember { mutableStateOf("") }
    val scroll = rememberScrollState()

    fun saveChannel() {
        val current = SettingsStore.current.copy(
            baseUrl = baseUrl.trim(),
            model = model.trim(),
        )
        val saved = SettingsStore.save(current)
        val hasKey = apiKey.isNotBlank() || SettingsStore.hasKey()
        val keySaved = apiKey.isBlank() || SettingsStore.setApiKey(apiKey.trim())
        message = when {
            !saved || !keySaved -> "保存失败：${SettingsStore.settingsPath()}"
            !hasKey -> "已保存地址和模型，但还需要填写 API Key"
            baseUrl.isBlank() || model.isBlank() -> "已保存 API Key，但 Base URL 和模型名还未填写"
            else -> {
                apiKey = ""
                "已保存大模型 API 配置"
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 34.dp, vertical = 22.dp)) {
        PageHeader("设置", "翻译模型、外观和安全")
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(24.dp)) {
                Text("翻译模型", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    "仅使用你自己填写的 OpenAI 兼容 API。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    placeholder = { Text("https://api.example.com/v1") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("模型名") },
                    placeholder = { Text("deepseek-chat / gpt-4o-mini / ...") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    placeholder = {
                        Text(if (SettingsStore.hasKey()) "已保存；留空则不修改" else "sk-...")
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { saveChannel() }, shape = RoundedCornerShape(12.dp)) {
                        Text("保存 API 配置")
                    }
                    if (message.isNotBlank()) {
                        Spacer(Modifier.width(12.dp))
                        Text(
                            message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (message.startsWith("保存失败")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 18.dp))
                SettingRow("深色外观", "使用低亮度界面", trailing = {
                    Switch(checked = dark, onCheckedChange = onDarkChange)
                })
                HorizontalDivider(Modifier.padding(vertical = 14.dp))
                SettingRow("只读模式", "阻止写入游戏文件", trailing = {
                    Switch(
                        checked = readOnly,
                        onCheckedChange = { next ->
                            readOnly = next
                            message = if (SettingsStore.save(SettingsStore.current.copy(readOnly = next))) {
                                ""
                            } else {
                                "设置保存失败：${SettingsStore.settingsPath()}"
                            }
                        },
                    )
                })
            }
        }
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(3.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}
