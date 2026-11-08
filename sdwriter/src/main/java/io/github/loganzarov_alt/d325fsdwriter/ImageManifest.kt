// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
// Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
package io.github.loganzarov_alt.d325fsdwriter

import org.json.JSONObject

data class ImageManifest(
    val format: String,
    val model: String,
    val rawSize: Long,
    val rawSha256: String,
    val gzipSize: Long,
    val gzipSha256: String,
    val minimumTargetBytes: Long,
    val sectorSize: Int,
    val userdataPartition: Int,
    val userdataStartSector: Long,
    val sanitizedPartitions: List<String>,
    val bundledModemPatcherVersion: String,
    val bundledModemPatcherSha256: String,
) {
    companion object {
        const val FORMAT = "d325-sd-image-v1"
        const val PUBLISHED_RAW_SIZE = 2_295_862_272L
        const val PUBLISHED_RAW_SHA256 = "1c99b5dcf07d72e0c617e3e2f6a8e1ab18892e2eada5d404c0494d35e4656a6e"
        private val SHA256 = Regex("[0-9a-f]{64}")

        fun parse(text: String): ImageManifest {
            val json = JSONObject(text)
            val sanitized = json.getJSONArray("sanitizedPartitions")
            val result = ImageManifest(
                format = json.getString("format"),
                model = json.getString("model"),
                rawSize = json.getLong("rawSize"),
                rawSha256 = json.getString("rawSha256").lowercase(),
                gzipSize = json.getLong("gzipSize"),
                gzipSha256 = json.getString("gzipSha256").lowercase(),
                minimumTargetBytes = json.getLong("minimumTargetBytes"),
                sectorSize = json.getInt("sectorSize"),
                userdataPartition = json.getInt("userdataPartition"),
                userdataStartSector = json.getLong("userdataStartSector"),
                sanitizedPartitions = List(sanitized.length()) { sanitized.getString(it) },
                bundledModemPatcherVersion = json.getString("bundledModemPatcherVersion"),
                bundledModemPatcherSha256 = json.getString("bundledModemPatcherSha256").lowercase(),
            )
            require(result.format == FORMAT || result.format == "$FORMAT-raw") { "Unsupported image format: ${result.format}" }
            require(result.model == "LG-D325") { "Image is not for LG D325" }
            require(result.rawSize > 0L && result.rawSize % 512L == 0L) { "Invalid raw image size" }
            require(result.gzipSize > 0L || result.format == "$FORMAT-raw") { "Invalid gzip size" }
            require(result.minimumTargetBytes >= result.rawSize) { "Target size is smaller than image size" }
            require(result.sectorSize == 512) { "Only 512-byte sectors are supported" }
            require(result.userdataPartition in 1..128) { "Invalid userdata partition number" }
            require(result.userdataStartSector in 34 until result.rawSize / 512L) { "Invalid userdata start" }
            require(SHA256.matches(result.rawSha256)) { "Invalid raw SHA-256" }
            require(SHA256.matches(result.gzipSha256)) { "Invalid gzip SHA-256" }
            require(SHA256.matches(result.bundledModemPatcherSha256)) { "Invalid Modem Patcher SHA-256" }
            require(result.sanitizedPartitions.containsAll(listOf("modemst1", "modemst2", "persist", "fsg", "fsc", "factory"))) {
                "Mandatory calibration partitions are not sanitized"
            }
            require(result.rawSize == PUBLISHED_RAW_SIZE && result.rawSha256 == PUBLISHED_RAW_SHA256) {
                "This writer only accepts the published sanitized D325 image"
            }
            return result
        }
    }
}
