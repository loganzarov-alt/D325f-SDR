package com.mantz_it.rfanalyzer

import com.mantz_it.rfanalyzer.ui.composable.SourceType
import com.mantz_it.rfanalyzer.ui.composable.FilesourceFileFormat
import org.junit.Assert.*
import org.junit.Test

class SourceSelectionTest {
    @Test fun onlyModemAndFileSourcesRemain() {
        assertEquals(listOf(SourceType.LGMODEM, SourceType.FILESOURCE), SourceType.entries.toList())
    }
    @Test fun legacyDeviceSelectionsFallBackToModemAndFileStaysFile() {
        for (old in listOf(-1, 0, 1, 2, 4, 99)) assertEquals(0, SourceType.migrateLegacyOrdinal(old))
        assertEquals(1, SourceType.migrateLegacyOrdinal(3))
    }
    @Test fun iqFormatDoesNotDependOnMenuOrdinal() {
        assertEquals(0, FilesourceFileFormat.CI8.nativeFormat)
        assertEquals(0, FilesourceFileFormat.HACKRF.nativeFormat)
        assertEquals(1, FilesourceFileFormat.RTLSDR.nativeFormat)
        assertEquals(2, FilesourceFileFormat.CI16.nativeFormat)
        assertEquals(3, FilesourceFileFormat.CI8.ordinal)
    }
}
