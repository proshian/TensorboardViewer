package com.example.tensorboardviewer.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.tensorboardviewer.data.model.RunData
import com.example.tensorboardviewer.data.model.ScalarPoint
import com.example.tensorboardviewer.data.model.ScalarSequence
import com.example.tensorboardviewer.data.parser.EventParser
import com.example.tensorboardviewer.data.parser.TensorBoardFileReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream

class LogRepository(private val context: Context) {

    suspend fun loadLogs(directoryUri: Uri): List<ScalarSequence> = withContext(Dispatchers.IO) {
        val rootDir = DocumentFile.fromTreeUri(context, directoryUri)
            ?: return@withContext emptyList()

        // Map: Tag -> (RunName -> List<ScalarPoint>)
        val scalarMap = mutableMapOf<String, MutableMap<String, MutableList<ScalarPoint>>>()

        // Recursively find all event files
        findEventFilesRecursively(rootDir, rootDir, scalarMap)

        // Convert to ScalarSequence with multiple runs
        scalarMap.map { (tag, runsMap) ->
            val runs = runsMap.map { (runName, points) ->
                RunData(runName, points.sortedBy { it.step })
            }
            ScalarSequence(tag, runs)
        }
    }

    private fun findEventFilesRecursively(
        currentDir: DocumentFile,
        rootDir: DocumentFile,
        scalarMap: MutableMap<String, MutableMap<String, MutableList<ScalarPoint>>>
    ) {
        val files = currentDir.listFiles()
        
        // Find event files in current directory
        val eventFiles = files.filter { it.isFile && it.name?.contains("tfevents") == true }
        
        if (eventFiles.isNotEmpty()) {
            // Use relative path as run name
            val runName = getRelativePath(rootDir, currentDir)
            
            for (file in eventFiles.sortedBy { it.name }) {
                try {
                    context.contentResolver.openInputStream(file.uri)?.use { inputStream ->
                        val bufferedInput = BufferedInputStream(inputStream)
                        val reader = TensorBoardFileReader(bufferedInput)

                        while (true) {
                            val event = reader.readEvent() ?: break
                            val scalars = EventParser.extractScalars(event)
                            for ((tag, point) in scalars) {
                                scalarMap.getOrPut(tag) { mutableMapOf() }
                                    .getOrPut(runName) { mutableListOf() }
                                    .add(point)
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        
        // Recursively search subdirectories
        files.filter { it.isDirectory }.forEach { subDir ->
            findEventFilesRecursively(subDir, rootDir, scalarMap)
        }
    }

    private fun getRelativePath(rootDir: DocumentFile, currentDir: DocumentFile): String {
        if (rootDir.uri == currentDir.uri) {
            return "root"
        }
        
        // Build path from directory names
        val pathSegments = mutableListOf<String>()
        var dir = currentDir
        
        // Traverse up to root (limited to avoid infinite loops)
        var depth = 0
        while (dir.uri != rootDir.uri && depth < 20) {
            dir.name?.let { pathSegments.add(0, it) }
            dir = dir.parentFile ?: break
            depth++
        }
        
        return if (pathSegments.isEmpty()) "root" else pathSegments.joinToString("/")
    }
}
