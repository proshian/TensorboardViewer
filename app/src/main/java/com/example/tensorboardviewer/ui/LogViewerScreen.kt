package com.example.tensorboardviewer.ui

import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.key
import androidx.compose.ui.viewinterop.AndroidView
import com.example.tensorboardviewer.data.model.ScalarSequence
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.listener.ChartTouchListener
import com.github.mikephil.charting.listener.OnChartGestureListener
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Composable
fun LogViewerScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()
    var selectedTag by remember { mutableStateOf<String?>(null) }
    
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { viewModel.loadLogs(it) }
    }

    Scaffold { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // Button at the top of content area
            Button(
                onClick = { launcher.launch(null) },
                modifier = Modifier.padding(16.dp).fillMaxWidth()
            ) {
                Text("Select Log Directory")
            }
            
            // Main content
            Box(modifier = Modifier.fillMaxSize()) {
                when (val s = state) {
                    is UiState.Idle -> {
                        Text("Select a folder to view logs", modifier = Modifier.align(Alignment.Center))
                    }
                    is UiState.Loading -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    is UiState.Error -> {
                        Text("Error: ${s.message}", color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.Center))
                    }
                    is UiState.Success -> {
                        LogContent(
                            sequences = s.sequences,
                            selectedTag = selectedTag,
                            onTagSelected = { selectedTag = it }
                        )
                    }
                }
            }
        }
    }
}


@Composable
fun LogContent(
    sequences: List<ScalarSequence>,
    selectedTag: String?,
    onTagSelected: (String) -> Unit
) {
    // If no tag selected, select first
    val currentTag = selectedTag ?: sequences.firstOrNull()?.tag
    var ignoreOutliers by remember { mutableStateOf(true) }
    var rangeSelectionEnabled by remember { mutableStateOf(false) }
    
    if (currentTag == null) return

    // Track visibility of each run across all tags (persist when switching tags)
    val visibilityState = remember {
        mutableStateOf<Map<String, Boolean>>(emptyMap())
    }
    
    // Add any new runs to visibility state (default visible)
    val currentSequence = sequences.find { it.tag == currentTag }
    currentSequence?.runs?.forEach { run ->
        if (!visibilityState.value.containsKey(run.runName)) {
            visibilityState.value = visibilityState.value + (run.runName to true)
        }
    }
    
    // Range selection state
    var rangeStart by remember { mutableStateOf<Float?>(null) }
    var rangeEnd by remember { mutableStateOf<Float?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Tag Selector
        LazyRow(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            items(sequences) { seq ->
                FilterChip(
                    selected = seq.tag == currentTag,
                    onClick = { onTagSelected(seq.tag) },
                    label = { Text(seq.tag) },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }

        // Outlier filtering option
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = ignoreOutliers,
                onCheckedChange = { ignoreOutliers = it }
            )
            Text("Ignore outliers in chart scaling", modifier = Modifier.padding(start = 8.dp))
        }
        
        // Range selection option
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = rangeSelectionEnabled,
                onCheckedChange = { 
                    rangeSelectionEnabled = it
                    if (!it) {
                        rangeStart = null
                        rangeEnd = null
                    }
                }
            )
            Text("Enable range selection", modifier = Modifier.padding(start = 8.dp))
        }

        // Chart
        // Use key to force recreation of the Chart (and reset zoom/scroll state) when tag or outlier setting changes
        key(currentTag, ignoreOutliers) {
            sequences.find { it.tag == currentTag }?.let { seq ->
                // Define colors for different runs
                val colors = listOf(
                    Color.BLUE, Color.RED, Color.rgb(0, 150, 0), 
                    Color.rgb(255, 165, 0), Color.rgb(128, 0, 128),
                    Color.CYAN, Color.MAGENTA, Color.rgb(139, 69, 19)
                )
                
                val dataSets = seq.runs.mapIndexed { index, run ->
                    // Downsample logic to prevent freezing
                    val maxPointsToRender = 1000
                    val points = run.points
                    val step = max(1, points.size / maxPointsToRender)
                    
                    val reducedPoints = if (points.size > maxPointsToRender) {
                        points.filterIndexed { idx, _ -> idx % step == 0 }
                    } else {
                        points
                    }
        
                    val entries = reducedPoints.map { point ->
                        Entry(point.step.toFloat(), point.value)
                    }
                    
                    LineDataSet(entries, run.runName).apply {
                        color = colors[index % colors.size]
                        lineWidth = 2f
                        setDrawCircles(false)
                        setDrawValues(false)
                        setDrawFilled(false)
                        mode = LineDataSet.Mode.LINEAR
                    }
                }
                
                if (dataSets.isNotEmpty() && dataSets.any { it.entryCount > 0 }) {
                    val rangeRenderer = remember { 
                        mutableStateOf<RangeMarkerRenderer?>(null) 
                    }
                    
                    AndroidView(
                        factory = { context ->
                            LineChart(context).apply {
                                description.isEnabled = false
                                setTouchEnabled(true)
                                
                                // Enable independent X and Y scaling
                                setPinchZoom(false) // Disable proportional pinch zoom
                                setScaleEnabled(true)
                                isScaleXEnabled = true
                                isScaleYEnabled = true
                                
                                // Enable dragging
                                isDragEnabled = true
                                
                                // Disable grid lines for cleaner look
                                xAxis.setDrawGridLines(false)
                                axisLeft.setDrawGridLines(false)
                                axisRight.isEnabled = false
                                
                                // Position X axis at bottom
                                xAxis.position = XAxis.XAxisPosition.BOTTOM
                                
                                // Disable built-in legend, we'll use custom toggles
                                legend.isEnabled = false
                                
                                // Create custom renderer for range selection
                                val customRenderer = RangeMarkerRenderer(
                                    this,
                                    animator,
                                    viewPortHandler
                                )
                                renderer = customRenderer
                                rangeRenderer.value = customRenderer
                                
                                // Add touch listener for handle dragging
                                setOnTouchListener { _, event ->
                                    if (rangeSelectionEnabled) {
                                        val handled = customRenderer.handleTouch(event)
                                        if (handled) {
                                            rangeStart = customRenderer.rangeStart
                                            rangeEnd = customRenderer.rangeEnd
                                            invalidate()
                                            return@setOnTouchListener true
                                        }
                                    }
                                    false
                                }
                            }
                        },
                        update = { chart ->
                            // Filter datasets based on visibility
                            val visibleDataSets = dataSets.filter { dataSet ->
                                visibilityState.value[dataSet.label] == true
                            }
                            
                            val lineData = if (visibleDataSets.isNotEmpty()) {
                                LineData(visibleDataSets)
                            } else {
                                LineData() // Empty data if all hidden
                            }
                            chart.data = lineData
                            
                            // Initialize range when enabled for the first time
                            if (rangeSelectionEnabled && rangeStart == null && rangeEnd == null && visibleDataSets.isNotEmpty()) {
                                // Find min and max X values across all visible datasets
                                val allXValues = visibleDataSets.flatMap { dataSet ->
                                    (0 until dataSet.entryCount).map { dataSet.getEntryForIndex(it).x }
                                }
                                if (allXValues.isNotEmpty()) {
                                    val minX = allXValues.minOrNull() ?: 0f
                                    val maxX = allXValues.maxOrNull() ?: 100f
                                    val rangeWidth = maxX - minX
                                    rangeStart = minX + rangeWidth * 0.25f
                                    rangeEnd = minX + rangeWidth * 0.75f
                                }
                            }
                            
                            // Update renderer with range values
                            rangeRenderer.value?.let { renderer ->
                                renderer.updateRange(rangeStart, rangeEnd)
                                renderer.isRangeSelectionEnabled = rangeSelectionEnabled
                            }
                            
                            // Apply outlier filtering if enabled
                            if (ignoreOutliers && visibleDataSets.isNotEmpty()) {
                                // Collect all values from visible datasets
                                val allValues = visibleDataSets.flatMap { dataSet ->
                                    (0 until dataSet.entryCount).map { dataSet.getEntryForIndex(it).y }
                                }
                                
                                if (allValues.size > 4) {
                                    val sortedValues = allValues.sorted()
                                    val q1Index = (sortedValues.size * 0.25).toInt()
                                    val q3Index = (sortedValues.size * 0.75).toInt()
                                    val q1 = sortedValues[q1Index]
                                    val q3 = sortedValues[q3Index]
                                    val iqr = q3 - q1
                                    
                                    val lowerBound = q1 - 1.5f * iqr
                                    val upperBound = q3 + 1.5f * iqr
                                    
                                    val filteredValues = sortedValues.filter { it in lowerBound..upperBound }
                                    
                                    if (filteredValues.isNotEmpty()) {
                                        val minY = filteredValues.minOrNull() ?: sortedValues.first()
                                        val maxY = filteredValues.maxOrNull() ?: sortedValues.last()
                                        val padding = (maxY - minY) * 0.1f
                                        
                                        chart.axisLeft.axisMinimum = minY - padding
                                        chart.axisLeft.axisMaximum = maxY + padding
                                        chart.notifyDataSetChanged()
                                        chart.invalidate()
                                    } else {
                                        chart.fitScreen()
                                    }
                                } else {
                                    chart.fitScreen()
                                }
                            } else {
                                chart.fitScreen()
                            }
                            chart.invalidate()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                            .padding(16.dp)
                    )
                    
                    // Custom visibility toggles (scrollable with LazyColumn for smoother scrolling)
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .padding(horizontal = 16.dp)
                    ) {
                        items(seq.runs.size) { index ->
                            val run = seq.runs[index]
                            val color = colors[index % colors.size]
                            val isVisible = visibilityState.value[run.runName] ?: true
                            
                            // Calculate min/max if range selection is enabled
                            val rangeStats = if (rangeSelectionEnabled && rangeStart != null && rangeEnd != null && isVisible) {
                                val pointsInRange = run.points.filter { point ->
                                    point.step.toFloat() >= rangeStart!! && point.step.toFloat() <= rangeEnd!!
                                }
                                if (pointsInRange.isNotEmpty()) {
                                    val minValue = pointsInRange.minOf { it.value }
                                    val maxValue = pointsInRange.maxOf { it.value }
                                    " Min: %.3f, Max: %.3f".format(minValue, maxValue)
                                } else null
                            } else null
                            
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Colored circle
                                Canvas(modifier = Modifier.size(12.dp)) {
                                    drawCircle(
                                        color = ComposeColor(
                                            android.graphics.Color.red(color),
                                            android.graphics.Color.green(color),
                                            android.graphics.Color.blue(color)
                                        ),
                                        radius = size.minDimension / 2
                                    )
                                }
                                
                                Spacer(modifier = Modifier.width(6.dp))
                                
                                // Checkbox
                                Checkbox(
                                    checked = isVisible,
                                    onCheckedChange = { checked ->
                                        visibilityState.value = visibilityState.value.toMutableMap().apply {
                                            put(run.runName, checked)
                                        }
                                    },
                                    modifier = Modifier.padding(0.dp)
                                )
                                
                                Spacer(modifier = Modifier.width(2.dp))
                                
                                // Run name with range stats in orange color
                                Text(
                                    text = buildAnnotatedString {
                                        append(run.runName)
                                        if (rangeStats != null) {
                                            withStyle(style = SpanStyle(color = ComposeColor(255, 100, 100))) {
                                                append(rangeStats)
                                            }
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        
                        // Select/Deselect all checkbox at the bottom
                        item {
                            val allSelected = seq.runs.all { visibilityState.value[it.runName] == true }
                            
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 0.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Spacer(modifier = Modifier.width(18.dp))
                                
                                Checkbox(
                                    checked = allSelected,
                                    onCheckedChange = { _ ->
                                        // If all selected, deselect all; otherwise select all
                                        val newState = !allSelected
                                        visibilityState.value = seq.runs.associate { it.runName to newState }
                                    },
                                    modifier = Modifier.padding(0.dp)
                                )
                                
                                Spacer(modifier = Modifier.width(2.dp))
                                
                                Text(
                                    text = if (allSelected) "Deselect All" else "Select All",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                } else {
                     Text("No data for this tag", modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}
