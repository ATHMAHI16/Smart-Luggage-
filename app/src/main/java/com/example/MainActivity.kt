package com.example

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlin.random.Random

// 1. Data Model
data class LuggageData(
    val totalWeight: Float, // kg
    val corner1Pct: Float, // Top Left (0.0 to 1.0)
    val corner2Pct: Float, // Top Right
    val corner3Pct: Float, // Bottom Left
    val corner4Pct: Float, // Bottom Right
    val cogX: Float, // -1f (left) to 1f (right)
    val cogY: Float, // -1f (top) to 1f (bottom)
    val timestamp: Long = System.currentTimeMillis()
)

enum class DataMode {
    SIMULATED,
    LIVE
}

enum class ConnectionState {
    IDLE,
    CONNECTING,
    CONNECTED,
    ERROR
}

// 2. ViewModel
class DashboardViewModel : ViewModel() {
    private val client = OkHttpClient()
    private val dbUrl = "https://smart-luggage-d4b56-default-rtdb.firebaseio.com/luggage_data.json"

    private val _luggageData = MutableStateFlow<LuggageData?>(null)
    val luggageData: StateFlow<LuggageData?> = _luggageData.asStateFlow()

    private val _airlineLimit = MutableStateFlow(23f)
    val airlineLimit = _airlineLimit.asStateFlow()

    private val _history = MutableStateFlow<List<LuggageData>>(emptyList())
    val history = _history.asStateFlow()

    private val _dataMode = MutableStateFlow(DataMode.SIMULATED)
    val dataMode = _dataMode.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.IDLE)
    val connectionState = _connectionState.asStateFlow()

    private val _errorMessage = MutableStateFlow("")
    val errorMessage = _errorMessage.asStateFlow()

    private var lastLoggedWeight = -1f

    init {
        startDataCollection()
    }

    fun setAirlineLimit(limit: Float) {
        _airlineLimit.value = limit
    }

    fun toggleDataMode() {
        _dataMode.update { current ->
            if (current == DataMode.SIMULATED) DataMode.LIVE else DataMode.SIMULATED
        }
    }

    private fun startDataCollection() {
        viewModelScope.launch {
            while (true) {
                if (_dataMode.value == DataMode.SIMULATED) {
                    _connectionState.value = ConnectionState.IDLE
                    // Simulate ESP32 sending data every 2 seconds
                    val limit = _airlineLimit.value
                    val isOverweight = Random.nextBoolean()
                    val totalWeight = if (isOverweight) {
                        limit + Random.nextFloat() * 5f // 0 to 5kg over
                    } else {
                        limit - Random.nextFloat() * 10f // 0 to 10kg under
                    }
                    
                    // Distribute load (somewhat realistically)
                    val c1 = Random.nextFloat()
                    val c2 = Random.nextFloat()
                    val c3 = Random.nextFloat()
                    val c4 = Random.nextFloat()
                    val sum = c1 + c2 + c3 + c4
                    
                    // Calculate simulated cog roughly based on weights
                    val topWeight = (c1 + c2) / sum
                    val bottomWeight = (c3 + c4) / sum
                    val leftWeight = (c1 + c3) / sum
                    val rightWeight = (c2 + c4) / sum
                    
                    val cogX = (rightWeight - leftWeight) * 2f // roughly -1 to 1
                    val cogY = (bottomWeight - topWeight) * 2f // roughly -1 to 1
                    
                    val newData = LuggageData(
                        totalWeight = maxOf(0f, totalWeight),
                        corner1Pct = c1 / sum,
                        corner2Pct = c2 / sum,
                        corner3Pct = c3 / sum,
                        corner4Pct = c4 / sum,
                        cogX = cogX.coerceIn(-1f, 1f),
                        cogY = cogY.coerceIn(-1f, 1f)
                    )
                    
                    _luggageData.value = newData
                    
                    // Log to history occasionally when weight significantly shifts
                    if (Math.abs(newData.totalWeight - lastLoggedWeight) > 0.5f && Random.nextInt(3) == 0) {
                        _history.update { current -> 
                            (listOf(newData) + current).take(20)
                        }
                        lastLoggedWeight = newData.totalWeight
                    }
                    delay(2000)
                } else {
                    // LIVE FIREBASE MODE
                    _connectionState.value = ConnectionState.CONNECTING
                    val request = Request.Builder().url(dbUrl).build()
                    try {
                        val result = withContext(Dispatchers.IO) {
                            client.newCall(request).execute().use { response ->
                                if (!response.isSuccessful) {
                                    throw Exception("HTTP Error: ${response.code}")
                                }
                                response.body?.string() ?: ""
                            }
                        }
                        
                        if (result.trim() == "null" || result.trim().isEmpty() || result.trim() == "{}") {
                            // Try calling root path as fallback
                            val rootRequest = Request.Builder()
                                .url("https://smart-luggage-d4b56-default-rtdb.firebaseio.com/.json")
                                .build()
                            val rootResult = withContext(Dispatchers.IO) {
                                client.newCall(rootRequest).execute().use { response ->
                                    response.body?.string() ?: ""
                                }
                            }
                            parseAndStoreJson(rootResult)
                        } else {
                            parseAndStoreJson(result)
                        }
                        _connectionState.value = ConnectionState.CONNECTED
                        _errorMessage.value = ""
                    } catch (e: Exception) {
                        Log.e("FirebaseConnect", "Connection error", e)
                        _connectionState.value = ConnectionState.ERROR
                        _errorMessage.value = e.localizedMessage ?: "Failed connecting to database"
                    }
                    delay(1500) // Poll every 1.5s
                }
            }
        }
    }

    private fun parseAndStoreJson(json: String) {
        if (json.trim() == "null" || json.isEmpty() || json.trim() == "{}") return
        val root = JSONObject(json)
        val dataObj = if (root.has("luggage_data")) {
            root.getJSONObject("luggage_data")
        } else {
            root
        }
        
        val totalWeight = dataObj.optDouble("total_weight", dataObj.optDouble("totalWeight", 0.0)).toFloat()
        var c1 = dataObj.optDouble("corner_1_pct", dataObj.optDouble("corner1Pct", 0.0)).toFloat()
        var c2 = dataObj.optDouble("corner_2_pct", dataObj.optDouble("corner2Pct", 0.0)).toFloat()
        var c3 = dataObj.optDouble("corner_3_pct", dataObj.optDouble("corner3Pct", 0.0)).toFloat()
        var c4 = dataObj.optDouble("corner_4_pct", dataObj.optDouble("corner4Pct", 0.0)).toFloat()
        
        // standardise corner input to 0..1 range if they are sent as 0..100
        if (c1 > 1f || c2 > 1f || c3 > 1f || c4 > 1f) {
            c1 /= 100f
            c2 /= 100f
            c3 /= 100f
            c4 /= 100f
        }
        
        val sum = c1 + c2 + c3 + c4
        val (n1, n2, n3, n4) = if (sum > 0f) {
            listOf(c1 / sum, c2 / sum, c3 / sum, c4 / sum)
        } else {
            listOf(0.25f, 0.25f, 0.25f, 0.25f)
        }
        
        val cogX = dataObj.optDouble("cog_x", dataObj.optDouble("cogX", 0.0)).toFloat()
        val cogY = dataObj.optDouble("cog_y", dataObj.optDouble("cogY", 0.0)).toFloat()
        
        val newData = LuggageData(
            totalWeight = maxOf(0f, totalWeight),
            corner1Pct = n1,
            corner2Pct = n2,
            corner3Pct = n3,
            corner4Pct = n4,
            cogX = cogX.coerceIn(-1f, 1f),
            cogY = cogY.coerceIn(-1f, 1f)
        )
        
        _luggageData.value = newData
        
        // Stabilize history updates on significant weight change
        if (Math.abs(newData.totalWeight - lastLoggedWeight) > 0.1f) {
            _history.update { current ->
                (listOf(newData) + current).take(20)
            }
            lastLoggedWeight = newData.totalWeight
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                DashboardScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(viewModel: DashboardViewModel = viewModel()) {
    val data by viewModel.luggageData.collectAsState()
    val limit by viewModel.airlineLimit.collectAsState()
    val history by viewModel.history.collectAsState()
    val mode by viewModel.dataMode.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Smart Luggage") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    AirlineSettingDropdown(limit) { newLimit -> viewModel.setAirlineLimit(newLimit) }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Mode Controller Widget
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Data Source Syncing", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = when (connectionState) {
                                    ConnectionState.IDLE -> "Using Offline Simulated Data"
                                    ConnectionState.CONNECTING -> "Syncing to Firebase Live..."
                                    ConnectionState.CONNECTED -> "Database Connected successfully!"
                                    ConnectionState.ERROR -> "Error: $errorMessage"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (connectionState == ConnectionState.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (mode == DataMode.LIVE) "Live" else "Sim",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Switch(
                                checked = mode == DataMode.LIVE,
                                onCheckedChange = { viewModel.toggleDataMode() }
                            )
                        }
                    }
                }
            }

            item {
                TotalWeightWidget(data?.totalWeight ?: 0f, limit)
            }
            
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        HeatmapWidget(data)
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        CenterOfGravityWidget(data)
                    }
                }
            }
            
            item {
                Text(
                    text = "Historical Weighing Log",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
            }
            
            items(history) { record ->
                HistoryItem(record)
            }
        }
    }
}

@Composable
fun AirlineSettingDropdown(currentLimit: Float, onLimitSelected: (Float) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val limits = listOf(15f, 23f, 32f)

    Box {
        TextButton(onClick = { expanded = true }) {
            Text("Limit: ${currentLimit.toInt()} kg", color = MaterialTheme.colorScheme.onPrimaryContainer)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            limits.forEach { limit ->
                DropdownMenuItem(
                    text = { Text("${limit.toInt()} kg") },
                    onClick = {
                        onLimitSelected(limit)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun TotalWeightWidget(weight: Float, limit: Float) {
    val isOverweight = weight > limit
    val color by animateColorAsState(
        if (isOverweight) MaterialTheme.colorScheme.error else Color(0xFF4CAF50),
        label = "weightColor"
    )
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(24.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Total Weight",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = String.format(java.util.Locale.US, "%.1f kg", weight),
                style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
                color = color
            )
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                color = color.copy(alpha = 0.2f),
                shape = RoundedCornerShape(percent = 50),
            ) {
                Text(
                    text = if (isOverweight) "OVERWEIGHT ALERT" else "COMPLIANT",
                    color = color,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
fun HeatmapWidget(data: LuggageData?) {
    Card(
        modifier = Modifier.fillMaxWidth().aspectRatio(0.8f),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Load Heatmap", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
            
            Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    HeatQuadrant(data?.corner1Pct ?: 0f, modifier = Modifier.weight(1f))
                    HeatQuadrant(data?.corner2Pct ?: 0f, modifier = Modifier.weight(1f))
                }
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    HeatQuadrant(data?.corner3Pct ?: 0f, modifier = Modifier.weight(1f))
                    HeatQuadrant(data?.corner4Pct ?: 0f, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun HeatQuadrant(percent: Float, modifier: Modifier = Modifier) {
    // 0 -> light yellow, 100% -> dark red.
    // In realistic scenarios, a corner maxes around 50%. Map roughly 0..0.5 to Yellow..Red
    val colorFactor = (percent * 2).coerceIn(0f, 1f)
    val color = lerp(Color(0xFFFFF59D), Color(0xFFD32F2F), colorFactor)
    val animatedColor by animateColorAsState(color, label = "quadrantColor")
    
    Box(
        modifier = modifier
            .padding(2.dp)
            .background(animatedColor, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "${(percent * 100).toInt()}%",
            color = Color.Black,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun CenterOfGravityWidget(data: LuggageData?) {
    Card(
        modifier = Modifier.fillMaxWidth().aspectRatio(0.8f),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Center of Gravity", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
            
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background, RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                val x by animateFloatAsState(data?.cogX ?: 0f, label = "cogX")
                val y by animateFloatAsState(data?.cogY ?: 0f, label = "cogY")
                val onBgColor = MaterialTheme.colorScheme.onBackground
                
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val lineColor = onBgColor.copy(alpha = 0.2f)
                    // Draw grid/crosshair
                    drawLine(
                        color = lineColor,
                        start = Offset(size.width / 2, 0f),
                        end = Offset(size.width / 2, size.height),
                        strokeWidth = 2f
                    )
                    drawLine(
                        color = lineColor,
                        start = Offset(0f, size.height / 2),
                        end = Offset(size.width, size.height / 2),
                        strokeWidth = 2f
                    )
                    
                    // Draw bounds
                    drawRect(
                        color = lineColor,
                        style = Stroke(width = 2f)
                    )
                    
                    // Plot point
                    val pointX = (x + 1f) / 2f * size.width
                    val pointY = (y + 1f) / 2f * size.height
                    
                    drawCircle(
                        color = Color(0xFF2196F3),
                        radius = 8.dp.toPx(),
                        center = Offset(pointX, pointY)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 3.dp.toPx(),
                        center = Offset(pointX, pointY)
                    )
                }
            }
        }
    }
}

@Composable
fun HistoryItem(record: LuggageData) {
    val formatter = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
    val dateString = formatter.format(java.util.Date(record.timestamp))
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(dateString, style = MaterialTheme.typography.bodyMedium)
            Text(
                String.format(java.util.Locale.US, "%.1f kg", record.totalWeight),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
            )
        }
    }
}
