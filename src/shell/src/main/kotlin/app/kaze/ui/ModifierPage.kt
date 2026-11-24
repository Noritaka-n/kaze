package app.kaze.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.sp
import app.kaze.data.MapGrid
import app.kaze.data.ModifierValues
import app.kaze.ipc.RelayState
import app.kaze.ipc.RelayStatus
import com.google.gson.JsonParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class ModTab(val label: String) { Switches("开关"), Variables("变量"), Maps("地图"), Events("事件"), Items("物品"), Cheats("作弊") }

private const val MOD_PAGE_SIZE = 20

private data class ModRow(
    val key: String,
    val name: String,
    val type: String,
    val label: String,
    val value: String,
    val meta: String = "",
    val depth: Int = 0,
)

private data class GameItem(val id: Int, val name: String, val count: Int, val kind: String, val icon: Int)

private data class PartyActor(
    val id: Int, val name: String, val className: String, val level: Int,
    val hp: Int, val mhp: Int, val mp: Int, val mmp: Int,
)

private data class StateInfo(val id: Int, val name: String, val icon: Int)
private data class BuffInfo(val param: Int, val name: String, val level: Int)
private data class ActorBuffs(val states: List<StateInfo>, val buffs: List<BuffInfo>)

private data class MapPreview(val mapId: Int, val name: String, val width: Int, val height: Int, val events: List<MapEvent>) {
    companion object {
        fun parse(json: String): MapPreview? = runCatching {
            val obj = JsonParser.parseString(json).asJsonObject
            if (obj.get("ok")?.asBoolean == false) return null
            MapPreview(
                obj.get("mapId")?.asInt ?: 0,
                obj.text("name"),
                obj.get("width")?.asInt ?: 0,
                obj.get("height")?.asInt ?: 0,
                obj.getAsJsonArray("events")?.mapNotNull { item ->
                    val e = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    MapEvent(e.get("id")?.asInt ?: 0, e.text("name"), e.get("x")?.asInt ?: 0, e.get("y")?.asInt ?: 0, e.text("kind"))
                }.orEmpty(),
            )
        }.getOrNull()
    }
}

private data class MapEvent(val id: Int, val name: String, val x: Int, val y: Int, val kind: String)
private data class PlayerPos(val mapId: Int, val x: Int, val y: Int) {
    companion object {
        fun parse(json: String): PlayerPos? = runCatching {
            val obj = JsonParser.parseString(json).asJsonObject
            PlayerPos(obj.get("mapId")?.asInt ?: 0, obj.get("x")?.asInt ?: 0, obj.get("y")?.asInt ?: 0)
        }.getOrNull()
    }
}

@Composable
fun ModifierPage() {
    val activeGame = remember {
        app.kaze.data.LibraryStore.games.firstOrNull { it.id == app.kaze.data.ActiveGame.id }
    }
    if (activeGame?.engine == app.kaze.data.Engine.RGSS) {
        RgssMemoryPage(activeGame)
        return
    }
    val scope = rememberCoroutineScope()
    var online by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(ModTab.Switches) }
    var rows by remember { mutableStateOf<List<ModRow>>(emptyList()) }
    var total by remember { mutableStateOf(0) }
    var page by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var selectedMap by remember { mutableStateOf<ModRow?>(null) }
    var mapGrid by remember { mutableStateOf<MapGrid?>(null) }
    var mapPreview by remember { mutableStateOf<MapPreview?>(null) }
    var playerPos by remember { mutableStateOf<PlayerPos?>(null) }
    var mapLoading by remember { mutableStateOf(false) }
    var expandedMaps by remember { mutableStateOf<Set<String>>(emptySet()) }
    var mapsInitialized by remember { mutableStateOf(false) }
    var targetTile by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var throughOn by remember { mutableStateOf(false) }
    var party by remember { mutableStateOf<List<PartyActor>>(emptyList()) }
    var partyBuffs by remember { mutableStateOf<Map<Int, ActorBuffs>>(emptyMap()) }
    var allStates by remember { mutableStateOf<List<StateInfo>>(emptyList()) }
    var gold by remember { mutableStateOf(0) }
    var goldText by remember { mutableStateOf("0") }
    var partyLoading by remember { mutableStateOf(false) }
    var encounterOn by remember { mutableStateOf(true) }
    var speed by remember { mutableStateOf(1) }
    var itemKind by remember { mutableStateOf("all") }
    var requestSerial by remember { mutableStateOf(0) }
    var refreshRetries by remember { mutableStateOf(0) }

    fun refresh() {
        if (tab == ModTab.Items) return
        val requestedTab = tab
        val requestedPage = page.coerceAtLeast(0)
        val serial = ++requestSerial
        loading = true
        message = ""
        val (op, args) = when (requestedTab) {
            ModTab.Switches -> if (query.isBlank())
                "vars.page" to """{"kind":"switch","offset":${requestedPage * MOD_PAGE_SIZE},"limit":$MOD_PAGE_SIZE}"""
            else
                "vars.search" to """{"kind":"switch","q":${ModifierValues.quote(query)},"offset":${requestedPage * MOD_PAGE_SIZE},"limit":$MOD_PAGE_SIZE}"""
            ModTab.Variables -> if (query.isBlank())
                "vars.page" to """{"kind":"var","offset":${requestedPage * MOD_PAGE_SIZE},"limit":$MOD_PAGE_SIZE}"""
            else
                "vars.search" to """{"kind":"var","q":${ModifierValues.quote(query)},"offset":${requestedPage * MOD_PAGE_SIZE},"limit":$MOD_PAGE_SIZE}"""
            ModTab.Maps -> "maps.list" to "{}"
            ModTab.Events -> "events.all" to "{}"
            ModTab.Items -> "party.items" to "{}"
            ModTab.Cheats -> "cheats.list" to "{}"
        }
        RelayState.invoke(op, args) { result ->
            scope.launch {
                if (serial != requestSerial) return@launch
                loading = false
                if (!result.ok) {
                    rows = emptyList()
                    total = 0
                    if (requestedTab == ModTab.Maps) {
                        selectedMap = null
                        mapPreview = null
                        mapGrid = null
                    }
                    message = result.detail.ifBlank { "读取失败" }
                    if (message.contains("adapter not connected", true) && refreshRetries < 2) {
                        refreshRetries++
                        RelayState.attach("")
                        delay(1200)
                        refresh()
                    }
                } else {
                    refreshRetries = 0
                    val parsed = parseRows(requestedTab, result.dataJson)
                    if ((requestedTab == ModTab.Switches || requestedTab == ModTab.Variables) && requestedPage > 0) {
                        val lastPage = if (parsed.second == 0) 0 else (parsed.second - 1) / MOD_PAGE_SIZE
                        if (requestedPage > lastPage) {
                            page = lastPage
                            return@launch
                        }
                    }
                    rows = parsed.first
                    total = parsed.second
                    message = ""
                    if (requestedTab == ModTab.Maps) {
                        if (!mapsInitialized) {
                            expandedMaps = rows.map { it.key }.toSet()
                            mapsInitialized = true
                        }
                        if (selectedMap == null) selectedMap = rows.firstOrNull()
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        RelayState.connectAsync()
        while (true) {
            online = RelayState.status is RelayStatus.Online
            delay(800)
        }
    }
    LaunchedEffect(tab) {
        requestSerial++
        refreshRetries = 0
        loading = false
        rows = emptyList()
        total = 0
        selectedMap = null
        mapPreview = null
        mapGrid = null
        message = ""
    }

    LaunchedEffect(tab, page, query, online) {
        if (query.isNotBlank()) delay(320)
        refresh()
    }
    LaunchedEffect(tab, selectedMap?.key, online) {
        if (tab != ModTab.Maps || !online) return@LaunchedEffect
        val id = selectedMap?.name?.toIntOrNull() ?: return@LaunchedEffect
        mapPreview = null
        mapGrid = null
        targetTile = null
        mapLoading = true
        RelayState.invoke("map.preview", """{"map":$id}""") { r ->
            scope.launch {
                if (selectedMap?.name?.toIntOrNull() != id) return@launch
                val parsed = if (r.ok) MapPreview.parse(r.dataJson) else null
                mapPreview = parsed
                if (parsed == null) {
                    mapGrid = null
                    message = r.detail.ifBlank { "地图预览读取失败" }
                }
                mapLoading = false
            }
        }
        RelayState.invoke("map.grid", """{"map":$id}""") { r ->
            scope.launch {
                if (selectedMap?.name?.toIntOrNull() != id) return@launch
                if (r.ok) {
                    val o = runCatching { JsonParser.parseString(r.dataJson).asJsonObject }.getOrNull()
                    if (o != null) mapGrid = MapGrid.decode(o.get("mapId")?.asInt ?: id, o.get("width")?.asInt ?: 0, o.get("height")?.asInt ?: 0, o.text("blocked"))
                    else mapGrid = null
                } else {
                    mapGrid = null
                    message = r.detail.ifBlank { "地图通行数据读取失败" }
                }
            }
        }
        RelayState.invoke("map.info", "{}") { r ->
            scope.launch {
                if (!r.ok || selectedMap?.name?.toIntOrNull() != id) return@launch
                val position = PlayerPos.parse(r.dataJson)
                playerPos = position
                targetTile = if (position?.mapId == id) position.x to position.y else null
            }
        }
    }

    fun loadParty() {
        partyLoading = true
        RelayState.invoke("actors.list", "{}") { r ->
            scope.launch {
                if (r.ok) {
                    party = runCatching {
                        JsonParser.parseString(r.dataJson).asJsonArray.mapNotNull { item ->
                            val o = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                            PartyActor(
                                id = o.get("id")?.asInt ?: return@mapNotNull null,
                                name = o.text("name"),
                                className = o.text("className"),
                                level = o.get("level")?.asInt ?: 1,
                                hp = o.get("hp")?.asInt ?: 0,
                                mhp = o.get("mhp")?.asInt ?: 0,
                                mp = o.get("mp")?.asInt ?: 0,
                                mmp = o.get("mmp")?.asInt ?: 0,
                            )
                        }
                    }.getOrDefault(emptyList())
                }
                partyLoading = false
            }
        }
        RelayState.invoke("party.gold", "{}") { r ->
            scope.launch {
                if (r.ok) {
                    val g = runCatching { JsonParser.parseString(r.dataJson).asJsonObject.get("gold")?.asInt ?: 0 }.getOrDefault(0)
                    gold = g
                    goldText = g.toString()
                }
            }
        }
        RelayState.invoke("actors.stateList", "{}") { r ->
            scope.launch {
                if (r.ok) allStates = parseStates(r.dataJson)
            }
        }
    }

    fun loadBuffs(actorId: Int) {
        RelayState.invoke("actors.buffs", """{"id":$actorId}""") { r ->
            scope.launch {
                if (r.ok) {
                    val parsed = parseActorBuffs(r.dataJson)
                    if (parsed != null) partyBuffs = partyBuffs + (actorId to parsed)
                }
            }
        }
    }

    LaunchedEffect(tab, online) {
        if (tab == ModTab.Cheats && online) loadParty()
    }

    fun runAction(id: String) {
        RelayState.invoke("cheats.run", """{"id":${ModifierValues.quote(id)}}""") { r ->
            scope.launch {
                message = r.detail
                when (id) {
                    "no_encounter" -> if (r.ok) encounterOn = false
                    "encounter" -> if (r.ok) encounterOn = true
                    "speed_1" -> if (r.ok) speed = 1
                    "speed_2" -> if (r.ok) speed = 2
                    "speed_3" -> if (r.ok) speed = 3
                }
                if (id == "victory" || id == "defeat" || id == "heal") loadParty()
            }
        }
    }

    fun toggle(row: ModRow) {
        RelayState.invoke("vars.set", """{"name":${ModifierValues.quote(row.name)},"value":${if (row.value == "True") "false" else "true"}}""") {
            scope.launch { refresh() }
        }
    }
    fun saveVar(row: ModRow, value: String) {
        val encoded = ModifierValues.encode(value, row.type)
        if (encoded == null) {
            message = "数值格式不正确，未发送修改"
            return
        }
        RelayState.invoke("vars.set", """{"name":${ModifierValues.quote(row.name)},"value":$encoded}""") { r ->
            scope.launch { message = r.detail; refresh() }
        }
    }
    fun runCheat(row: ModRow) {
        RelayState.invoke("cheats.run", """{"id":${ModifierValues.quote(row.name)}}""") { r -> scope.launch { message = r.detail } }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 34.dp, vertical = 22.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val (title, subtitle) = when (tab) {
                    ModTab.Switches -> "开关" to "查看并切换游戏开关"
                    ModTab.Variables -> "变量" to "查看并修改游戏变量"
                    ModTab.Maps -> "地图" to "地图层级、通行性与传送"
                    ModTab.Events -> "事件" to "游戏内全部事件与坐标"
                    ModTab.Items -> "物品" to "搜索并添加道具、武器与防具"
                    ModTab.Cheats -> "作弊" to "队伍、金币与战斗功能"
                }
                Text(title, style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(4.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RelayChip(online) { RelayState.connectAsync() }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ModTab.entries.forEach { item -> ModTabButton(item.label, tab == item) { tab = item; page = 0; query = ""; message = "" } }
            if (tab == ModTab.Switches || tab == ModTab.Variables || tab == ModTab.Items) {
                Spacer(Modifier.width(6.dp))
                SearchBox(query, if (tab == ModTab.Items) "搜索物品" else "搜索名称或值") { query = it; page = 0 }
                if (tab == ModTab.Items) {
                    Spacer(Modifier.width(8.dp))
                    ItemKindMenu(itemKind) { itemKind = it }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                ModTab.Switches -> SwitchGrid(rows, loading, ::toggle)
                ModTab.Variables -> VariableTable(rows, total, loading, ::saveVar)
                ModTab.Maps -> MapWorkspace(
                    rows = rows, selected = selectedMap, preview = mapPreview, grid = mapGrid,
                    player = playerPos, target = targetTile, throughOn = throughOn,
                    mapLoading = mapLoading, listLoading = loading,
                    expanded = expandedMaps,
                    onToggle = { key -> expandedMaps = if (key in expandedMaps) expandedMaps - key else expandedMaps + key },
                    onPick = { x, y -> targetTile = x to y },
                    onThrough = {
                        val next = !throughOn
                        RelayState.invoke("cheats.run", """{"id":${ModifierValues.quote(if (next) "through_on" else "through_off")}}""") { r ->
                            if (r.ok) scope.launch { throughOn = next }
                        }
                    },
                    onTeleport = {
                        val id = selectedMap?.name?.toIntOrNull()
                        val tile = targetTile
                        if (id != null && tile != null) {
                            RelayState.invoke("player.teleport", """{"map":$id,"x":${tile.first},"y":${tile.second}}""") { r ->
                                scope.launch { message = r.detail }
                            }
                        }
                    },
                    onRefresh = ::refresh,
                    onSelect = { selectedMap = it },
                )
                ModTab.Events -> EventCards(rows, loading)
                ModTab.Items -> ItemPage(query, { query = it }, online, itemKind)
                ModTab.Cheats -> CheatDashboard(
                    party = party,
                    buffs = partyBuffs,
                    allStates = allStates,
                    goldText = goldText,
                    partyLoading = partyLoading,
                    encounterOn = encounterOn,
                    speed = speed,
                    throughOn = throughOn,
                    onGoldText = { goldText = it },
                    onApplyGold = {
                        val value = goldText.toIntOrNull()?.takeIf { it >= 0 }
                        if (value == null) {
                            message = "金币必须是非负整数，未发送修改"
                        } else {
                            RelayState.invoke("party.setGold", """{"gold":$value}""") { r ->
                                scope.launch { message = r.detail; loadParty() }
                            }
                        }
                    },
                    onAddGold = { goldText = ((goldText.toIntOrNull() ?: gold) + 9999).toString() },
                    onActorSet = { actor, level, hp, mp ->
                        val fields = buildList {
                            level?.let { add("\"level\":$it") }
                            hp?.let { add("\"hp\":$it") }
                            mp?.let { add("\"mp\":$it") }
                            if (level != null && hp == null && mp == null) add("\"full\":true")
                        }
                        if (fields.isNotEmpty()) {
                            RelayState.invoke("actors.set", """{"id":${actor.id},${fields.joinToString(",")}}""") { r ->
                                scope.launch { message = r.detail; loadParty() }
                            }
                        }
                    },
                    onLoadBuffs = { id -> loadBuffs(id) },
                    onAddState = { actorId, stateId ->
                        RelayState.invoke("actors.setState", """{"id":$actorId,"stateId":$stateId,"add":true}""") { r ->
                            scope.launch { message = r.detail; loadBuffs(actorId) }
                        }
                    },
                    onRemoveState = { actorId, stateId ->
                        RelayState.invoke("actors.setState", """{"id":$actorId,"stateId":$stateId,"add":false}""") { r ->
                            scope.launch { message = r.detail; loadBuffs(actorId) }
                        }
                    },
                    onSetBuff = { actorId, param, level ->
                        RelayState.invoke("actors.setBuff", """{"id":$actorId,"param":$param,"level":$level}""") { r ->
                            scope.launch { message = r.detail; loadBuffs(actorId) }
                        }
                    },
                    onAction = ::runAction,
                    onCustomSpeed = { value ->
                        RelayState.invoke("speed.set", """{"multiplier":$value}""") { r ->
                            scope.launch { message = r.detail; if (r.ok) speed = value }
                        }
                    },
                )
            }
        }
        if ((tab == ModTab.Switches || tab == ModTab.Variables) && total > MOD_PAGE_SIZE) {
            Pager(page, total) { target -> page = target.coerceIn(0, (total - 1) / MOD_PAGE_SIZE) }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            message,
            modifier = Modifier.height(18.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SearchBox(value: String, placeholder: String = "搜索名称或值", onValueChange: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.width(248.dp).height(40.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Search, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (value.isBlank()) {
                        Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    inner()
                },
            )
        }
    }
}

@Composable
internal fun FlatChip(
    label: String,
    selected: Boolean,
    height: Int = 34,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) Color.Transparent else MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
        modifier = Modifier.height(height.dp).clickable(
            interactionSource = interaction,
            indication = null,
            onClick = onClick,
        ),
    ) {
        Box(Modifier.padding(horizontal = 11.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun RelayChip(online: Boolean, onRefresh: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (online) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.height(32.dp),
    ) {
        Row(Modifier.padding(start = 11.dp, end = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline))
            Spacer(Modifier.width(6.dp))
            Text(if (online) "已连接" else "未连接", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(3.dp))
            Box(Modifier.size(24.dp).clip(RoundedCornerShape(50)).clickable { onRefresh() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Refresh, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ModTabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        tween(160), label = "tab-bg",
    )
    val labelColor by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        tween(160), label = "tab-fg",
    )
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = container,
        border = BorderStroke(1.dp, if (selected) Color.Transparent else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.height(34.dp).clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = labelColor)
        }
    }
}

@Composable
private fun SwitchGrid(rows: List<ModRow>, loading: Boolean, onToggle: (ModRow) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(210.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.weight(1f),
        ) {
            gridItems(rows, key = { it.key }) { row ->
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(row.label.ifBlank { row.name }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(3.dp))
                            Text(row.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = row.value == "True", onCheckedChange = { onToggle(row) })
                    }
                }
            }
        }
    }
}

@Composable
private fun VariableTable(
    rows: List<ModRow>, total: Int, loading: Boolean,
    onSave: (ModRow, String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text("变量", Modifier.weight(1.2f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("名称", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("值", Modifier.weight(1.3f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (loading) CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("$total", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
                    items(rows, key = { it.key }) { row ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(row.name, Modifier.weight(1.2f), style = MaterialTheme.typography.bodyMedium)
                            Text(row.label.ifBlank { "—" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            ValueCell(row, Modifier.weight(1.3f)) { value -> onSave(row, value) }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ValueCell(row: ModRow, modifier: Modifier = Modifier, onCommit: (String) -> Unit) {
    var text by remember(row.key, row.value) { mutableStateOf(row.value) }
    var hadFocus by remember { mutableStateOf(false) }
    BasicTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .height(28.dp)
            .onFocusChanged {
                if (hadFocus && !it.isFocused) onCommit(text)
                hadFocus = it.isFocused
            }
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyUp && it.key == Key.Enter) {
                    onCommit(text)
                    true
                } else false
            },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(7.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) { inner() }
        },
    )
}

@Composable
private fun MapWorkspace(
    rows: List<ModRow>,
    selected: ModRow?,
    preview: MapPreview?,
    grid: MapGrid?,
    player: PlayerPos?,
    target: Pair<Int, Int>?,
    throughOn: Boolean,
    mapLoading: Boolean,
    listLoading: Boolean,
    expanded: Set<String>,
    onToggle: (String) -> Unit,
    onPick: (Int, Int) -> Unit,
    onThrough: () -> Unit,
    onTeleport: () -> Unit,
    onRefresh: () -> Unit,
    onSelect: (ModRow) -> Unit,
) {
    val parentOf = IntArray(rows.size) { -1 }
    val stack = mutableListOf<Int>()
    rows.forEachIndexed { index, row ->
        while (stack.isNotEmpty() && rows[stack.last()].depth >= row.depth) stack.removeAt(stack.lastIndex)
        parentOf[index] = stack.lastOrNull() ?: -1
        stack.add(index)
    }
    val hasChildren = BooleanArray(rows.size) { index -> parentOf.any { it == index } }
    fun visible(index: Int): Boolean {
        var parent = parentOf[index]
        while (parent >= 0) {
            if (rows[parent].key !in expanded) return false
            parent = parentOf[parent]
        }
        return true
    }
    val visibleRows = rows.indices.filter(::visible)

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(
            shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.width(310.dp).fillMaxHeight(),
        ) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 13.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("地图树", style = MaterialTheme.typography.titleLarge)
                        Text("${rows.size} 张地图", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (listLoading) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    TextButton(onClick = onRefresh, contentPadding = PaddingValues(4.dp)) { Icon(Icons.Outlined.Refresh, null, Modifier.size(15.dp)) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                LazyColumn(Modifier.fillMaxSize()) {
                    items(visibleRows, key = { rows[it].key }) { index ->
                        val row = rows[index]
                        val isSelected = selected?.key == row.key
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onSelect(row) }
                                .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .padding(start = (6 + row.depth * 16).dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(22.dp).clickable(enabled = hasChildren[index]) { onToggle(row.key) }, contentAlignment = Alignment.Center) {
                                if (hasChildren[index]) {
                                    Text(if (row.key in expanded) "▾" else "▸", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                                }
                            }
                            if (row.depth > 0) {
                                Box(Modifier.width(1.dp).height(18.dp).background(MaterialTheme.colorScheme.outlineVariant))
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                row.label.ifBlank { "地图 ${row.name}" },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (hasChildren[index]) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.padding(18.dp)) {
                if (mapLoading && preview == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
                } else if (preview == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("选择左侧地图查看网格", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    Text(preview.name.ifBlank { "地图 ${preview.mapId}" }, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(3.dp))
                    Text("${preview.mapId} · ${preview.width}×${preview.height} · ${preview.events.size} 个事件", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    MapCanvas(preview, grid, player, target, onPick)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("● 事件", color = Color(0xFF3B6559), style = MaterialTheme.typography.labelMedium)
                        Text("● 玩家", color = Color(0xFFD06B4A), style = MaterialTheme.typography.labelMedium)
                        Text("● 目标", color = Color(0xFFE0A24A), style = MaterialTheme.typography.labelMedium)
                        Text("深色 = 阻挡", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            target?.let { "目标 ${it.first}, ${it.second}" } ?: "点击地图选择目标格",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = onTeleport, enabled = target != null, shape = RoundedCornerShape(11.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) { Text("传送") }
                        Button(
                            onClick = onThrough,
                            shape = RoundedCornerShape(11.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = if (throughOn) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (throughOn) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        ) { Text(if (throughOn) "关闭穿墙" else "开启穿墙") }
                    }
                }
            }
        }
    }
}

@Composable
private fun MapCanvas(preview: MapPreview, grid: MapGrid?, player: PlayerPos?, target: Pair<Int, Int>?, onPick: (Int, Int) -> Unit) {
    val w = (grid?.width ?: preview.width).coerceAtLeast(1)
    val h = (grid?.height ?: preview.height).coerceAtLeast(1)
    Canvas(
        Modifier.fillMaxWidth().aspectRatio(w.toFloat() / h).clip(RoundedCornerShape(14.dp)).background(Color(0xFFF1F5F0))
            .pointerInput(preview.mapId, w, h) {
                detectTapGestures { offset ->
                    val x = (offset.x / (size.width.toFloat() / w)).toInt().coerceIn(0, w - 1)
                    val y = (offset.y / (size.height.toFloat() / h)).toInt().coerceIn(0, h - 1)
                    onPick(x, y)
                }
            },
    ) {
        val cw = size.width / w
        val ch = size.height / h
        if (grid != null && w * h <= 30000) {
            for (y in 0 until h) for (x in 0 until w) if (grid.isBlocked(x, y)) {
                drawRect(Color(0xFFD6E1D8), Offset(x * cw, y * ch), Size(cw + 0.4f, ch + 0.4f))
            }
        }
        val dot = (minOf(cw, ch) * 0.8f).coerceIn(3f, 6f)
        preview.events.forEach { e ->
            drawCircle(Color(0xFF3B6559), dot, Offset((e.x + 0.5f) * cw, (e.y + 0.5f) * ch))
        }
        if (player != null && player.mapId == preview.mapId) {
            drawCircle(Color(0xFFD06B4A), dot, Offset((player.x + 0.5f) * cw, (player.y + 0.5f) * ch))
        }
        target?.let { (x, y) ->
            drawCircle(Color(0xFFE0A24A), dot, Offset((x + 0.5f) * cw, (y + 0.5f) * ch))
        }
    }
}

@Composable
private fun EventCards(rows: List<ModRow>, loading: Boolean) {
    Column(Modifier.fillMaxSize()) {
        LazyVerticalGrid(columns = GridCells.Adaptive(250.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp), modifier = Modifier.fillMaxSize()) {
            gridItems(rows, key = { it.key }) { row ->
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(15.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(row.label.ifBlank { row.name }, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("#${row.name}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(row.value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        if (row.meta.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(row.meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheatDashboard(
    party: List<PartyActor>,
    buffs: Map<Int, ActorBuffs>,
    allStates: List<StateInfo>,
    goldText: String,
    partyLoading: Boolean,
    encounterOn: Boolean,
    speed: Int,
    throughOn: Boolean,
    onGoldText: (String) -> Unit,
    onApplyGold: () -> Unit,
    onAddGold: () -> Unit,
    onActorSet: (PartyActor, Int?, Int?, Int?) -> Unit,
    onLoadBuffs: (Int) -> Unit,
    onAddState: (Int, Int) -> Unit,
    onRemoveState: (Int, Int) -> Unit,
    onSetBuff: (Int, Int, Int) -> Unit,
    onAction: (String) -> Unit,
    onCustomSpeed: (Int) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            GoldCard(goldText, onGoldText, onApplyGold, onAddGold)
            Surface(
                shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.weight(1f),
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("队伍", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(3.dp))
                        Text("${party.size} 名角色 · 等级、HP、MP 与状态", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (partyLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        if (party.isEmpty() && !partyLoading) {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth()) {
                Text("队伍为空，或尚未连接到战斗中的游戏。", Modifier.padding(18.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(party, key = { it.id }) { actor ->
                    ActorCard(
                        actor = actor,
                        info = buffs[actor.id],
                        allStates = allStates,
                        onActorSet = onActorSet,
                        onLoadBuffs = onLoadBuffs,
                        onAddState = onAddState,
                        onRemoveState = onRemoveState,
                        onSetBuff = onSetBuff,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        ClassicActions(encounterOn, speed, throughOn, onAction, onCustomSpeed)
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun GoldCard(value: String, onValue: (String) -> Unit, onApply: () -> Unit, onAdd: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.width(284.dp)) {
        Column(Modifier.padding(18.dp)) {
            Text("金币", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = value,
                    onValueChange = onValue,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.headlineSmall.copy(color = MaterialTheme.colorScheme.onPrimaryContainer),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth().height(38.dp).clip(RoundedCornerShape(9.dp)).background(Color.White.copy(alpha = 0.55f)).padding(horizontal = 9.dp), contentAlignment = Alignment.CenterStart) { inner() }
                    },
                )
                Spacer(Modifier.width(6.dp))
                Text("G", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onAdd, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("+9999") }
                Button(onClick = onApply, shape = RoundedCornerShape(9.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp)) { Text("应用") }
            }
        }
    }
}

@Composable
private fun ActorCard(
    actor: PartyActor,
    info: ActorBuffs?,
    allStates: List<StateInfo>,
    onActorSet: (PartyActor, Int?, Int?, Int?) -> Unit,
    onLoadBuffs: (Int) -> Unit,
    onAddState: (Int, Int) -> Unit,
    onRemoveState: (Int, Int) -> Unit,
    onSetBuff: (Int, Int, Int) -> Unit,
) {
    var level by remember(actor.id, actor.level) { mutableStateOf(actor.level.toString()) }
    var hp by remember(actor.id, actor.hp) { mutableStateOf(actor.hp.toString()) }
    var mp by remember(actor.id, actor.mp) { mutableStateOf(actor.mp.toString()) }
    val hpRatio = if (actor.mhp > 0) actor.hp.toFloat() / actor.mhp else 0f
    val mpRatio = if (actor.mmp > 0) actor.mp.toFloat() / actor.mmp else 0f
    LaunchedEffect(actor.id) { onLoadBuffs(actor.id) }
    LaunchedEffect(level, hp, mp) {
        delay(650)
        val lv = level.toIntOrNull()
        val h = hp.toIntOrNull()
        val m = mp.toIntOrNull()
        when {
            lv != null && lv != actor.level -> onActorSet(actor, lv, null, null)
            h != null && h != actor.hp -> onActorSet(actor, null, h, null)
            m != null && m != actor.mp -> onActorSet(actor, null, null, m)
        }
    }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.width(315.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Text(actor.name.take(1).ifBlank { "?" }, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(actor.name.ifBlank { "角色 ${actor.id}" }, style = MaterialTheme.typography.titleLarge)
                    Text(actor.className.ifBlank { "未知职业" }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Lv.${actor.level}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(12.dp))
            Text("HP ${actor.hp} / ${actor.mhp}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(progress = { hpRatio.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text("MP ${actor.mp} / ${actor.mmp}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(progress = { mpRatio.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)), color = Color(0xFF5B7FA3), trackColor = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(Modifier.height(11.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                StatField("Lv", level, { level = it }, Modifier.weight(1f))
                StatField("HP", hp, { hp = it }, Modifier.weight(1f))
                StatField("MP", mp, { mp = it }, Modifier.weight(1f))
            }
            Text("自动应用 · 改等级后 HP/MP 以游戏结算为准", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Spacer(Modifier.height(4.dp))
            if (info == null) {
                Text("读取状态…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                if (info.states.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        items(info.states, key = { it.id }) { state ->
                            Surface(shape = RoundedCornerShape(7.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                Row(Modifier.padding(start = 7.dp, end = 3.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(state.name.ifBlank { "状态 ${state.id}" }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Text("×", Modifier.padding(horizontal = 5.dp).clickable { onRemoveState(actor.id, state.id) }, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                }
                AddStateMenu(actor.id, allStates, info.states.map { it.id }.toSet(), onAddState)
                if (info.buffs.isNotEmpty()) {
                    Spacer(Modifier.height(5.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        items(info.buffs, key = { it.param }) { buff ->
                            Surface(shape = RoundedCornerShape(7.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(buff.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("−", Modifier.padding(horizontal = 5.dp).clickable { onSetBuff(actor.id, buff.param, (buff.level - 1).coerceAtLeast(-2)) }, style = MaterialTheme.typography.labelMedium)
                                    Text(if (buff.level > 0) "+${buff.level}" else "${buff.level}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                    Text("+", Modifier.padding(horizontal = 5.dp).clickable { onSetBuff(actor.id, buff.param, (buff.level + 1).coerceAtMost(2)) }, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatField(label: String, value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BasicTextField(
            value = value, onValueChange = onValue, singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().height(28.dp),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)).padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) { inner() }
            },
        )
    }
}

@Composable
private fun AddStateMenu(actorId: Int, states: List<StateInfo>, activeIds: Set<Int>, onAdd: (Int, Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)) { Text("+ 添加状态", style = MaterialTheme.typography.labelMedium) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            states.filter { it.id !in activeIds }.take(40).forEach { state ->
                DropdownMenuItem(
                    text = { Text(state.name.ifBlank { "状态 ${state.id}" }) },
                    onClick = { open = false; onAdd(actorId, state.id) },
                )
            }
        }
    }
}

@Composable
private fun ClassicActions(encounterOn: Boolean, speed: Int, throughOn: Boolean, onAction: (String) -> Unit, onCustomSpeed: (Int) -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text("功能", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { onAction(if (encounterOn) "no_encounter" else "encounter") }, shape = RoundedCornerShape(10.dp)) {
                    Text(if (encounterOn) "关闭遇敌" else "恢复遇敌")
                }
                Button(onClick = { onAction("victory") }, shape = RoundedCornerShape(10.dp)) { Text("直接胜利") }
                Button(onClick = { onAction("defeat") }, shape = RoundedCornerShape(10.dp)) { Text("直接失败") }
                Button(onClick = { onAction("heal") }, shape = RoundedCornerShape(10.dp)) { Text("全员满血") }
                TextButton(onClick = { onAction("items99") }) { Text("道具各 +99") }
                TextButton(onClick = { onAction(if (throughOn) "through_off" else "through_on") }) { Text(if (throughOn) "关闭穿墙" else "开启穿墙") }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("游戏倍速", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                listOf(1, 2, 3).forEach { value ->
                    FlatChip("×$value", speed == value, height = 30) { onAction("speed_$value") }
                }
                var customOpen by remember { mutableStateOf(false) }
                var customText by remember { mutableStateOf(if (speed in 1..3) "" else speed.toString()) }
                FlatChip(if (speed !in 1..3) "×$speed" else "自定义", speed !in 1..3, height = 30) { customOpen = true }
                if (customOpen) {
                    Dialog(onDismissRequest = { customOpen = false }) {
                        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                            Column(Modifier.width(280.dp).padding(20.dp)) {
                                Text("自定义倍速", style = MaterialTheme.typography.titleLarge)
                                Spacer(Modifier.height(10.dp))
                                BasicTextField(
                                    value = customText,
                                    onValueChange = { customText = it.filter(Char::isDigit).take(2) },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.headlineSmall.copy(color = MaterialTheme.colorScheme.onSurface),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.fillMaxWidth().height(40.dp),
                                    decorationBox = { inner ->
                                        Box(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) { inner() }
                                    },
                                )
                                Spacer(Modifier.height(5.dp))
                                Text("范围 1–10", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { customOpen = false }) { Text("取消") }
                                    Button(
                                        onClick = {
                                            val value = (customText.toIntOrNull() ?: speed).coerceIn(1, 10)
                                            onCustomSpeed(value)
                                            customOpen = false
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                    ) { Text("应用") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheatCards(rows: List<ModRow>, loading: Boolean, onRun: (ModRow) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("快捷操作", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            if (loading) CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
            Text("${rows.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        LazyVerticalGrid(columns = GridCells.Adaptive(260.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
            gridItems(rows, key = { it.key }) { row ->
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(row.label, style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(5.dp))
                        Text(row.value, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { onRun(row) }, shape = RoundedCornerShape(11.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) { Text("执行") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Pager(page: Int, total: Int, onPage: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { if (page > 0) onPage(page - 1) }, enabled = page > 0) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, Modifier.size(15.dp)) }
        Text("${page + 1} / ${((total + MOD_PAGE_SIZE - 1) / MOD_PAGE_SIZE).coerceAtLeast(1)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { if ((page + 1) * MOD_PAGE_SIZE < total) onPage(page + 1) }, enabled = (page + 1) * MOD_PAGE_SIZE < total) { Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(15.dp)) }
    }
}

private fun parseRows(tab: ModTab, json: String): Pair<List<ModRow>, Int> {
    val element = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return emptyList<ModRow>() to 0
    return when (tab) {
        ModTab.Switches, ModTab.Variables -> {
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList<ModRow>() to 0
            val items = obj.getAsJsonArray("items") ?: return emptyList<ModRow>() to 0
            items.mapNotNull { item ->
                val e = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                ModRow(e.text("name"), e.text("name"), e.text("type"), e.text("label"), e.text("value"))
            }.let { it to (obj.get("total")?.asInt ?: it.size) }
        }
        ModTab.Maps -> {
            val items = element.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList<ModRow>() to 0
            val infos = items.mapNotNull { item ->
                item.takeIf { it.isJsonObject }?.asJsonObject?.let { e ->
                    val id = e.get("id")?.asInt ?: return@mapNotNull null
                    Triple(id, e.text("name"), e.get("parentId")?.asInt ?: 0)
                }
            }
            val byId = infos.associateBy { it.first }
            fun depthOf(id: Int, guard: Set<Int> = emptySet()): Int {
                val parent = byId[id]?.third ?: 0
                if (parent <= 0 || parent == id || parent in guard) return 0
                return 1 + depthOf(parent, guard + id)
            }
            infos.map { (id, name, _) -> ModRow("map:$id", id.toString(), "map", name.ifBlank { "地图 $id" }, "ID $id", depth = depthOf(id)) } to infos.size
        }
        ModTab.Events -> {
            val items = element.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList<ModRow>() to 0
            items.mapNotNull { item ->
                val e = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val id = e.get("id")?.asInt ?: return@mapNotNull null
                val mapId = e.get("mapId")?.asInt ?: 0
                val mapName = e.text("mapName").ifBlank { "地图 $mapId" }
                val pages = e.get("pages")?.asInt ?: 0
                ModRow(
                    "map:$mapId:event:$id", id.toString(), "event",
                    e.text("name").ifBlank { "事件 $id" },
                    "$mapName · ${e.text("x")}, ${e.text("y")} · ${e.text("kind")}",
                    if (pages > 0) "$pages 页 · ${e.text("note")}" else e.text("note"),
                )
            }.let { it to it.size }
        }
        ModTab.Items -> emptyList<ModRow>() to 0
        ModTab.Cheats -> {
            val items = element.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList<ModRow>() to 0
            items.mapNotNull { item ->
                val e = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val id = e.text("id").ifBlank { return@mapNotNull null }
                ModRow(id, id, "cheat", e.text("name").ifBlank { id }, e.text("desc"))
            }.let { it to it.size }
        }
    }
}

@Composable
private fun ItemKindMenu(kind: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = when (kind) { "item" -> "道具"; "weapon" -> "武器"; "armor" -> "防具"; else -> "全部" }
    Box {
        FlatChip("$label  ▾", kind != "all", height = 40) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf("all" to "全部", "item" to "道具", "weapon" to "武器", "armor" to "防具").forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text, fontWeight = if (kind == value) FontWeight.SemiBold else FontWeight.Normal) },
                    onClick = { open = false; onSelect(value) },
                )
            }
        }
    }
}

@Composable
private fun ItemPage(query: String, onQuery: (String) -> Unit, online: Boolean, kind: String) {
    var items by remember { mutableStateOf<List<GameItem>>(emptyList()) }
    var count by remember { mutableStateOf(1) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var requestSerial by remember { mutableStateOf(0) }
    var retries by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    fun load() {
        val serial = ++requestSerial
        loading = true
        RelayState.invoke("party.items", "{}") { r ->
            scope.launch {
                if (serial != requestSerial) return@launch
                loading = false
                if (r.ok) {
                    retries = 0
                    items = runCatching {
                        JsonParser.parseString(r.dataJson).asJsonArray.mapNotNull { item ->
                            val o = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                            GameItem(
                                o.get("id")?.asInt ?: return@mapNotNull null,
                                o.text("name"),
                                o.get("count")?.asInt ?: 0,
                                o.text("kind"),
                                o.get("iconIndex")?.asInt ?: 0,
                            )
                        }
                    }.getOrDefault(emptyList())
                    status = ""
                } else {
                    status = r.detail
                    if (status.contains("adapter not connected", ignoreCase = true) && retries < 2) {
                        retries++
                        delay(1200)
                        load()
                    }
                }
            }
        }
    }

    LaunchedEffect(online) { if (online) load() }
    val shown = items.filter {
        (kind == "all" || it.kind == kind) &&
            (query.isBlank() || it.name.contains(query, ignoreCase = true))
    }

    Column(Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("添加数量", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf(1, 10, 99).forEach { value ->
                FlatChip("+$value", count == value, height = 30) { count = value }
            }
            Spacer(Modifier.weight(1f))
            if (loading) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Text("共 ${shown.size} 项", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { load() }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) { Icon(Icons.Outlined.Refresh, null, Modifier.size(15.dp)) }
        }
        Spacer(Modifier.height(10.dp))
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
                items(shown, key = { it.kind + ":" + it.id }) { item ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Text(
                                when (item.kind) { "weapon" -> "武器"; "armor" -> "防具"; else -> "道具" },
                                Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.name.ifBlank { "物品 ${item.id}" }, style = MaterialTheme.typography.bodyLarge)
                            Text("#${item.id} · 拥有 ${item.count}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(
                            onClick = {
                                RelayState.invoke("party.addItem", """{"kind":${ModifierValues.quote(item.kind)},"id":${item.id},"count":$count}""") { r ->
                                    scope.launch { status = r.detail; load() }
                                }
                            },
                            shape = RoundedCornerShape(9.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) { Text("添加 +$count") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            status,
            modifier = Modifier.height(18.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun parseStates(json: String): List<StateInfo> = runCatching {
    JsonParser.parseString(json).asJsonArray.mapNotNull { item ->
        val o = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val id = o.get("id")?.asInt ?: return@mapNotNull null
        StateInfo(id, o.text("name"), o.get("icon")?.asInt ?: 0)
    }
}.getOrDefault(emptyList())

private fun parseActorBuffs(json: String): ActorBuffs? = runCatching {
    val o = JsonParser.parseString(json).asJsonObject
    val states = o.getAsJsonArray("states")?.mapNotNull { item ->
        val s = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        StateInfo(s.get("id")?.asInt ?: 0, s.text("name"), s.get("icon")?.asInt ?: 0)
    }.orEmpty()
    val buffs = o.getAsJsonArray("buffs")?.mapNotNull { item ->
        val b = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        BuffInfo(b.get("param")?.asInt ?: 0, b.text("name"), b.get("level")?.asInt ?: 0)
    }.orEmpty()
    ActorBuffs(states, buffs)
}.getOrNull()

private fun com.google.gson.JsonObject.text(name: String): String =
    get(name)?.takeIf { !it.isJsonNull }?.let { if (it.isJsonPrimitive) it.asString else it.toString() }.orEmpty()
