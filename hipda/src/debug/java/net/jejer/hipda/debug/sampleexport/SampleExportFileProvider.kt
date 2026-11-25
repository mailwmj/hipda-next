package net.jejer.hipda.debug.sampleexport

import androidx.core.content.FileProvider

/**
 * Debug-only [FileProvider] subclass with a dedicated authority so it never collides with
 * the main manifest's `${applicationId}.provider`.
 */
class SampleExportFileProvider : FileProvider()
