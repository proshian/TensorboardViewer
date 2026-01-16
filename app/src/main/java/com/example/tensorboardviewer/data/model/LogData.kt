package com.example.tensorboardviewer.data.model

data class ScalarPoint(
    val step: Long,
    val wallTime: Double,
    val value: Float
)

data class RunData(
    val runName: String,
    val points: List<ScalarPoint>
)

data class ScalarSequence(
    val tag: String,
    val runs: List<RunData>
)
