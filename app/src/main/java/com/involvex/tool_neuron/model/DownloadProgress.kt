package com.involvex.tool_neuron.model

sealed interface DownloadProgress {
    data object Indeterminate : DownloadProgress
    data class Determinate(val fraction: Float) : DownloadProgress
}
