package com.example.presentation.ui

import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.domain.model.DownloadTask
import com.example.engine.torrent.TorrentClient
import com.example.presentation.DownloadViewModel
import java.io.File
import kotlin.math.roundToInt

// Cyber Slate Color Scheme
val DarkSlateBg = Color(0xFF12141C)
val CardSlateBg = Color(0xFF1C1E2A)
val BrightNeonCyan = Color(0xFF00E5FF)
val BrightNeonEmerald = Color(0xFF00E676)
val BrightNeonOrange = Color(0xFFFF9100)
val SoftGray = Color(0xFF9E9E9E)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadHubApp(
    viewModel: DownloadViewModel = viewModel()
) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val activeSpeeds by viewModel.activeSpeeds.collectAsStateWithLifecycle()
    val activePeers by viewModel.activePeers.collectAsStateWithLifecycle()

    var showAddTaskDialog by varOf(false)

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkSlateBg),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "SmartDownload Hub",
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.SansSerif,
                            color = Color.White,
                            fontSize = 20.sp
                        )
                        Text(
                            text = "Acelerador HTTP & Engine BitTorrent",
                            color = SoftGray,
                            fontSize = 11.sp
                        )
                    }
                },
                actions = {
                    val totalSpeed = activeSpeeds.values.sum()
                    if (totalSpeed > 0) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = BrightNeonEmerald.copy(alpha = 0.15f),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = "Velocidade total",
                                    tint = BrightNeonEmerald,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = formatSpeed(totalSpeed),
                                    color = BrightNeonEmerald,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkSlateBg,
                    titleContentColor = Color.White
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddTaskDialog = true },
                containerColor = BrightNeonCyan,
                contentColor = DarkSlateBg,
                modifier = Modifier
                    .navigationBarsPadding()
                    .testTag("add_task_fab")
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "Adicionar Tarefa", modifier = Modifier.size(28.dp))
            }
        },
        containerColor = DarkSlateBg
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (tasks.isEmpty()) {
                EmptyStateView(onAddClick = { showAddTaskDialog = true })
            } else {
                TaskList(
                    tasks = tasks,
                    activePeers = activePeers,
                    viewModel = viewModel
                )
            }

            if (showAddTaskDialog) {
                AddTaskDialog(
                    onDismiss = { showAddTaskDialog = false },
                    onAdd = { name, url, type, isStreaming, priority, lowStorage, mirror ->
                        viewModel.addTask(name, url, type, isStreaming, priority, lowStorage, mirror)
                        showAddTaskDialog = false
                    }
                )
            }
        }
    }
}

@Composable
fun EmptyStateView(onAddClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = "Nenhum download",
            tint = SoftGray,
            modifier = Modifier.size(80.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Pronto para acelerar",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Adicione um link HTTP ou magnet link BitTorrent clicando no botão abaixo.",
            color = SoftGray,
            fontSize = 13.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onAddClick,
            colors = ButtonDefaults.buttonColors(containerColor = BrightNeonCyan, contentColor = DarkSlateBg)
        ) {
            Icon(imageVector = Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Nova Tarefa", fontWeight = FontWeight.Bold)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TaskList(
    tasks: List<DownloadTask>,
    activePeers: Map<String, List<TorrentClient.SwarmPeer>>,
    viewModel: DownloadViewModel
) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = 80.dp, top = 8.dp, start = 14.dp, end = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(tasks, key = { it.id }) { task ->
            TaskCard(
                task = task,
                peers = activePeers[task.id] ?: emptyList(),
                viewModel = viewModel,
                modifier = Modifier.animateItemPlacement()
            )
        }
    }
}

@Composable
fun TaskCard(
    task: DownloadTask,
    peers: List<TorrentClient.SwarmPeer>,
    viewModel: DownloadViewModel,
    modifier: Modifier = Modifier
) {
    var expanded by varOf(false)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .testTag("task_card_${task.id}"),
        colors = CardDefaults.cardColors(containerColor = CardSlateBg),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Task Header: Type Icon, Title, Status badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    val iconType = when (task.type) {
                        "TORRENT" -> Icons.Default.Refresh
                        "HYBRID" -> Icons.Default.Share
                        else -> Icons.Default.ArrowDropDown
                    }
                    val iconColor = when (task.type) {
                        "TORRENT" -> BrightNeonOrange
                        "HYBRID" -> BrightNeonCyan
                        else -> BrightNeonEmerald
                    }
                    Icon(
                        imageVector = iconType,
                        contentDescription = task.type,
                        tint = iconColor,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = task.name,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))
                StatusBadge(status = task.status)
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Subtitle metadata: bytes downloaded/total, speed, ETA
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "${formatSize(task.downloadedBytes)} / ${formatSize(task.totalBytes)}",
                    color = SoftGray,
                    fontSize = 11.sp
                )

                if (task.status == "DOWNLOADING" || task.status == "EXTRACTING") {
                    Text(
                        text = formatSpeed(task.speedBytesPerSec),
                        color = BrightNeonEmerald,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Progress bar
            LinearProgressIndicator(
                progress = { task.progress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = when (task.status) {
                    "ERROR" -> Color.Red
                    "COMPLETED" -> BrightNeonCyan
                    "EXTRACTING" -> BrightNeonCyan
                    else -> BrightNeonEmerald
                },
                trackColor = Color.White.copy(alpha = 0.1f)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Stats row & ETA
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${task.progress.roundToInt()}%",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    if (task.isStreamingExtraction) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Streaming Extractor",
                            tint = BrightNeonCyan,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text("Streaming Extractor", color = BrightNeonCyan, fontSize = 9.sp)
                    }
                }

                if (task.status == "DOWNLOADING" && task.etaSeconds > 0) {
                    Text(
                        text = "ETA: ${formatEta(task.etaSeconds)}",
                        color = SoftGray,
                        fontSize = 11.sp
                    )
                }
            }

            // Expandable settings and info area
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Divider(color = Color.White.copy(alpha = 0.1f))
                    Spacer(modifier = Modifier.height(10.dp))

                    // Speed limiter slider
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Limite de Velocidade", color = Color.White, fontSize = 12.sp)
                        Text(
                            text = if (task.speedLimitBytesPerSec <= 0) "Ilimitado" else formatSpeed(task.speedLimitBytesPerSec),
                            color = BrightNeonCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = when (task.speedLimitBytesPerSec) {
                            0L -> 0f
                            else -> task.speedLimitBytesPerSec.toFloat() / (10 * 1024 * 1024) // 10MB limit max
                        },
                        onValueChange = { sliderVal ->
                            val limit = (sliderVal * 10 * 1024 * 1024).toLong()
                            viewModel.updateSpeedLimit(task.id, if (limit < 50000L) 0L else limit)
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = BrightNeonCyan,
                            activeTrackColor = BrightNeonCyan,
                            inactiveTrackColor = Color.White.copy(alpha = 0.1f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Switches
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            Text("Modo Baixo Armazenamento", color = Color.White, fontSize = 12.sp)
                            Text("Usa buffer RAM mínimo de 4MB", color = SoftGray, fontSize = 9.sp)
                        }
                        Switch(
                            checked = task.isLowStorageMode,
                            onCheckedChange = { viewModel.updateLowStorageMode(task.id, it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = BrightNeonCyan,
                                checkedTrackColor = BrightNeonCyan.copy(alpha = 0.4f)
                            )
                        )
                    }

                    // Display swarm details for Torrent & Hybrid tasks
                    if (task.type == "TORRENT" || task.type == "HYBRID") {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Swarm de Peers Conectados (${peers.size})",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        if (peers.isEmpty()) {
                            Text("Buscando peers...", color = SoftGray, fontSize = 11.sp)
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                peers.take(4).forEach { peer ->
                                    Row(
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color.White.copy(alpha = 0.03f), RoundedCornerShape(4.dp))
                                            .padding(6.dp)
                                    ) {
                                        Text(peer.ip, color = SoftGray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                        Text(
                                            text = "${formatSpeed(peer.speedBytesPerSec)} | ${(peer.progress * 100).toInt()}%",
                                            color = BrightNeonEmerald,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Buttons actions
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = {
                                if (task.status == "DOWNLOADING" || task.status == "EXTRACTING") {
                                    viewModel.pauseTask(task.id)
                                } else {
                                    viewModel.startTask(task.id)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (task.status == "DOWNLOADING") BrightNeonOrange else BrightNeonEmerald,
                                contentColor = DarkSlateBg
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (task.status == "DOWNLOADING") Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (task.status == "DOWNLOADING") "Pausar" else "Iniciar",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Button(
                            onClick = { viewModel.removeTask(task.id) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Red.copy(alpha = 0.15f),
                                contentColor = Color.Red
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Excluir", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatusBadge(status: String) {
    val bgColor = when (status) {
        "DOWNLOADING" -> BrightNeonEmerald.copy(alpha = 0.15f)
        "EXTRACTING" -> BrightNeonCyan.copy(alpha = 0.15f)
        "COMPLETED" -> BrightNeonCyan.copy(alpha = 0.15f)
        "PAUSED" -> Color.White.copy(alpha = 0.1f)
        "ERROR" -> Color.Red.copy(alpha = 0.15f)
        else -> Color.White.copy(alpha = 0.1f)
    }

    val textColor = when (status) {
        "DOWNLOADING" -> BrightNeonEmerald
        "EXTRACTING" -> BrightNeonCyan
        "COMPLETED" -> BrightNeonCyan
        "PAUSED" -> SoftGray
        "ERROR" -> Color.Red
        else -> Color.White
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bgColor
    ) {
        Text(
            text = when (status) {
                "DOWNLOADING" -> "Baixando"
                "EXTRACTING" -> "Extraindo"
                "COMPLETED" -> "Concluído"
                "PAUSED" -> "Pausado"
                "ERROR" -> "Erro"
                else -> "Aguardando"
            },
            color = textColor,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTaskDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, url: String, type: String, isStreaming: Boolean, priority: Int, lowStorage: Boolean, mirror: String?) -> Unit
) {
    var name by varOf("big_buck_bunny.zip")
    var url by varOf("https://sample-videos.com/zip/100mb.zip")
    var type by varOf("HTTP") // "HTTP", "TORRENT", "HYBRID"
    var isStreaming by varOf(true)
    var isLowStorage by varOf(false)
    var priority by varOf(2) // 1=Low, 2=Medium, 3=High
    var httpMirror by varOf("")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Nova Tarefa de Download",
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontSize = 18.sp
            )
        },
        containerColor = CardSlateBg,
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Nome do Arquivo", color = SoftGray) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = DarkSlateBg,
                            unfocusedContainerColor = DarkSlateBg,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("URL HTTP ou Magnet Link", color = SoftGray) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = DarkSlateBg,
                            unfocusedContainerColor = DarkSlateBg,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    Text("Tipo de Conexão", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        listOf("HTTP", "TORRENT", "HYBRID").forEach { t ->
                            val selected = type == t
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (selected) BrightNeonCyan else DarkSlateBg,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        type = t
                                        if (t == "TORRENT") {
                                            name = "debian_netinst.torrent"
                                            url = "magnet:?xt=urn:btih:6fd1a43a12cd02bc&dn=debian"
                                        } else if (t == "HYBRID") {
                                            name = "hybrid_archive.zip"
                                            url = "magnet:?xt=urn:btih:6fd1a43a12cd02bc&dn=archive"
                                            httpMirror = "https://sample-videos.com/zip/100mb.zip"
                                        } else {
                                            name = "big_buck_bunny.zip"
                                            url = "https://sample-videos.com/zip/100mb.zip"
                                        }
                                    }
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(vertical = 10.dp)
                                ) {
                                    Text(
                                        t,
                                        color = if (selected) DarkSlateBg else Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }

                if (type == "HYBRID") {
                    item {
                        OutlinedTextField(
                            value = httpMirror,
                            onValueChange = { httpMirror = it },
                            label = { Text("URL Espelho HTTP", color = SoftGray) },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = DarkSlateBg,
                                unfocusedContainerColor = DarkSlateBg,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            Text("Extração em Streaming", color = Color.White, fontSize = 12.sp)
                            Text("Extrai o ZIP/TAR.GZ enquanto baixa", color = SoftGray, fontSize = 9.sp)
                        }
                        Switch(
                            checked = isStreaming,
                            onCheckedChange = { isStreaming = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = BrightNeonCyan, checkedTrackColor = BrightNeonCyan.copy(alpha = 0.4f))
                        )
                    }
                }

                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            Text("Modo Zero-Cache", color = Color.White, fontSize = 12.sp)
                            Text("Economia extrema de armazenamento", color = SoftGray, fontSize = 9.sp)
                        }
                        Switch(
                            checked = isLowStorage,
                            onCheckedChange = { isLowStorage = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = BrightNeonCyan, checkedTrackColor = BrightNeonCyan.copy(alpha = 0.4f))
                        )
                    }
                }

                item {
                    Text("Prioridade", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        listOf(1 to "Baixa", 2 to "Média", 3 to "Alta").forEach { (pVal, pText) ->
                            val selected = priority == pVal
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (selected) BrightNeonCyan else DarkSlateBg,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { priority = pVal }
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(vertical = 10.dp)
                                ) {
                                    Text(
                                        pText,
                                        color = if (selected) DarkSlateBg else Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onAdd(name, url, type, isStreaming, priority, isLowStorage, if (type == "HYBRID") httpMirror else null)
                },
                colors = ButtonDefaults.buttonColors(containerColor = BrightNeonCyan, contentColor = DarkSlateBg)
            ) {
                Text("Adicionar", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", color = BrightNeonCyan)
            }
        }
    )
}

fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "Desconhecido"
    val kb = bytes / 1024f
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024f
    if (mb < 1024) return String.format("%.1f MB", mb)
    val gb = mb / 1024f
    return String.format("%.1f GB", gb)
}

fun formatSpeed(bytesPerSec: Long): String {
    if (bytesPerSec <= 0) return "0 B/s"
    val kb = bytesPerSec / 1024f
    if (kb < 1024) return String.format("%.1f KB/s", kb)
    val mb = kb / 1024f
    return String.format("%.1f MB/s", mb)
}

fun formatEta(seconds: Long): String {
    if (seconds <= 0) return "..."
    val mins = seconds / 60
    val secs = seconds % 60
    if (mins <= 0) return "${secs}s"
    return "${mins}m ${secs}s"
}

// Utility extension to create clean inline state holders in Compose
@Composable
fun <T> varOf(initialValue: T): MutableState<T> = remember { mutableStateOf(initialValue) }
