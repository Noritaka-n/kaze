package app.kaze.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.kaze.data.ModifierValues
import app.kaze.ipc.RelayState
import com.google.gson.JsonParser
import java.io.File
import kotlinx.coroutines.launch

@Composable
fun RgssMemoryPage(game: app.kaze.data.Game) {
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf("int") }
    var scanValue by remember { mutableStateOf("") }
    var writeValue by remember { mutableStateOf("") }
    var selectedAddress by remember { mutableStateOf<Long?>(null) }
    var rows by remember { mutableStateOf<List<MemRow>>(emptyList()) }
    var message by remember { mutableStateOf("先附加进程，再输入数值扫描。") }
    var loading by remember { mutableStateOf(false) }

    fun loadResults() {
        RelayState.invoke("mem.results", "{}") { result ->
            scope.launch {
                loading = false
                if (!result.ok) {
                    message = result.detail.ifBlank { "读取结果失败" }
                    return@launch
                }
                rows = runCatching {
                    val array = JsonParser.parseString(result.dataJson).asJsonObject.getAsJsonArray("rows")
                    array.mapNotNull { element ->
                        val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                        val address = item.get("address")?.asLong ?: return@mapNotNull null
                        MemRow(address, item.get("hex")?.asString ?: "0x${address.toString(16)}", item.get("value")?.asString ?: "")
                    }
                }.getOrDefault(emptyList())
                selectedAddress = rows.firstOrNull()?.address
                message = "找到 ${rows.size} 个候选地址；修改一个值后点“再次筛选”。"
            }
        }
    }

    fun attach() {
        loading = true
        val name = File(game.launcher).name.ifBlank { "Game.exe" }
        RelayState.invoke("proc.attach", """{"exe":${ModifierValues.quote(name)}}""") { result ->
            scope.launch {
                loading = false
                message = if (result.ok) "已附加进程：$name" else "附加失败：${result.detail}"
            }
        }
    }

    fun scan(filter: Boolean) {
        val number = scanValue.trim()
        if (number.isBlank()) {
            message = "先输入要搜索的数值"
            return
        }
        val numeric = if (type == "float") number.toDoubleOrNull()?.toString() else number.toLongOrNull()?.toString()
        if (numeric == null) {
            message = "数值格式不正确"
            return
        }
        loading = true
        val op = if (filter) "mem.filter" else "mem.scan"
        RelayState.invoke(op, """{"value":$numeric,"type":"$type"}""") { result ->
            scope.launch {
                if (!result.ok) {
                    loading = false
                    message = result.detail.ifBlank { "扫描失败" }
                } else {
                    loadResults()
                }
            }
        }
    }

    fun write() {
        val address = selectedAddress ?: run {
            message = "先在列表里选择一个地址"
            return
        }
        val number = writeValue.trim()
        val numeric = if (type == "float") number.toDoubleOrNull()?.toString() else number.toLongOrNull()?.toString()
        if (numeric == null) {
            message = "写入值格式不正确"
            return
        }
        loading = true
        RelayState.invoke("mem.write", """{"address":$address,"type":"$type","value":$numeric}""") { result ->
            scope.launch {
                loading = false
                message = if (result.ok) "已写入 ${rows.firstOrNull { it.address == address }?.hex ?: address.toString()}" else "写入失败：${result.detail}"
                if (result.ok) loadResults()
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 34.dp, vertical = 22.dp)) {
        Text("RGSS 内存修改", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "RPG Maker XP / VX / VX Ace · 附加游戏进程后按数值扫描并写回",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("类型", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlatChip("32 位整数", type == "int", height = 34) { type = "int"; rows = emptyList(); selectedAddress = null }
                    FlatChip("浮点数", type == "float", height = 34) { type = "float"; rows = emptyList(); selectedAddress = null }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { attach() }, enabled = !loading) { Text("附加进程") }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = scanValue,
                        onValueChange = { scanValue = it },
                        placeholder = { Text("当前数值，例如金币 1000") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { scan(false) }, enabled = !loading, shape = RoundedCornerShape(12.dp)) {
                        Text("首次扫描")
                    }
                    Button(onClick = { scan(true) }, enabled = !loading && rows.isNotEmpty(), shape = RoundedCornerShape(12.dp)) {
                        Text("再次筛选")
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = writeValue,
                onValueChange = { writeValue = it },
                placeholder = { Text("写入的新数值") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { write() }, enabled = !loading && selectedAddress != null, shape = RoundedCornerShape(12.dp)) {
                Text("写入选中地址")
            }
        }
        Spacer(Modifier.height(12.dp))
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            if (rows.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("还没有扫描结果", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { it.address }) { row ->
                        val selected = row.address == selectedAddress
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { selectedAddress = row.address }
                                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(row.hex, Modifier.width(150.dp), style = MaterialTheme.typography.bodyMedium)
                            Text(row.value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                            if (selected) Text("已选中", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }
}

private data class MemRow(val address: Long, val hex: String, val value: String)
