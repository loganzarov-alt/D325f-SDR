// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
// Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
package io.github.loganzarov_alt.d325fsdwriter

import android.content.ContentResolver
import android.net.Uri
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

data class VerifiedImage(
    val manifest: ImageManifest,
    val rawBytes: Long,
)

class ImageArchive(private val resolver: ContentResolver, private val uri: Uri) {
    val sourceLabel: String by lazy { if (isZipArchive()) "compressed .d325img" else "raw .img" }

    fun readManifest(): ImageManifest = if (isZipArchive()) readArchiveManifest() else knownRawManifest()

    private fun readArchiveManifest(): ImageManifest = openZip().use { zip ->
        val entry = zip.nextEntry ?: error("Image archive is empty")
        require(entry.name == MANIFEST) { "manifest.json must be the first archive entry" }
        require(entry.size == -1L || entry.size in 1..MAX_MANIFEST_BYTES) { "Invalid manifest size" }
        val text = zip.readBytesLimited(MAX_MANIFEST_BYTES).toString(Charsets.UTF_8)
        ImageManifest.parse(text)
    }

    fun verify(progress: (Long, Long) -> Unit): VerifiedImage {
        val manifest = readManifest()
        if (!isZipArchive()) return verifyRaw(manifest, progress)
        var rawCount = 0L
        val rawDigest = MessageDigest.getInstance("SHA-256")
        val gzipDigest = MessageDigest.getInstance("SHA-256")
        openImageEntry().use { imageEntry ->
            val compressed = CountingDigestInputStream(imageEntry, gzipDigest)
            GZIPInputStream(compressed, BUFFER_SIZE).use { raw ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val count = raw.read(buffer)
                    if (count < 0) break
                    rawCount += count
                    require(rawCount <= manifest.rawSize) { "Raw image is larger than declared" }
                    rawDigest.update(buffer, 0, count)
                    progress(rawCount, manifest.rawSize)
                }
            }
            require(compressed.count == manifest.gzipSize) { "Compressed image size mismatch: ${compressed.count}" }
        }
        require(rawCount == manifest.rawSize) { "Raw image size mismatch: $rawCount" }
        require(rawDigest.hex() == manifest.rawSha256) { "Raw image SHA-256 mismatch" }
        require(gzipDigest.hex() == manifest.gzipSha256) { "Compressed image SHA-256 mismatch" }
        return VerifiedImage(manifest, rawCount)
    }

    fun copyRawTo(output: java.io.OutputStream, manifest: ImageManifest, progress: (Long, Long) -> Unit) {
        val rawDigest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        val rawInput = if (isZipArchive()) {
            GZIPInputStream(openImageEntry(), BUFFER_SIZE)
        } else {
            BufferedInputStream(requireNotNull(resolver.openInputStream(uri)) { "Cannot open selected image" }, BUFFER_SIZE)
        }
        rawInput.use { raw ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = raw.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    rawDigest.update(buffer, 0, read)
                    count += read
                    require(count <= manifest.rawSize) { "Raw image is larger than declared" }
                    progress(count, manifest.rawSize)
                }
        }
        output.flush()
        require(count == manifest.rawSize) { "Raw image size changed after verification" }
        require(rawDigest.hex() == manifest.rawSha256) { "Raw image changed after verification" }
    }

    private fun verifyRaw(manifest: ImageManifest, progress: (Long, Long) -> Unit): VerifiedImage {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        BufferedInputStream(
            requireNotNull(resolver.openInputStream(uri)) { "Cannot open selected image" },
            BUFFER_SIZE,
        ).use { raw ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = raw.read(buffer)
                if (read < 0) break
                count += read
                require(count <= manifest.rawSize) { "Raw image is larger than the published sanitized image" }
                digest.update(buffer, 0, read)
                progress(count, manifest.rawSize)
            }
        }
        require(count == manifest.rawSize) { "Raw image size mismatch: $count" }
        require(digest.hex() == manifest.rawSha256) {
            "Raw image SHA-256 is not the published sanitized D325 image"
        }
        return VerifiedImage(manifest, count)
    }

    private fun isZipArchive(): Boolean {
        val magic = ByteArray(4)
        val count = requireNotNull(resolver.openInputStream(uri)) { "Cannot open selected image" }.use {
            it.read(magic)
        }
        require(count == magic.size) { "Selected image is too short" }
        return magic.contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
    }

    private fun knownRawManifest(): ImageManifest = ImageManifest(
        format = "${ImageManifest.FORMAT}-raw",
        model = "LG-D325",
        rawSize = ImageManifest.PUBLISHED_RAW_SIZE,
        rawSha256 = ImageManifest.PUBLISHED_RAW_SHA256,
        gzipSize = 0,
        gzipSha256 = ZERO_SHA256,
        minimumTargetBytes = ImageManifest.PUBLISHED_RAW_SIZE,
        sectorSize = 512,
        userdataPartition = 34,
        userdataStartSector = 4_353_024,
        sanitizedPartitions = listOf(
            "pad", "modemst1", "modemst2", "misc", "persist", "fsg", "fsc", "ssd", "DDR",
            "encrypt", "rct", "drm", "sns", "factory", "fota", "mpt", "cust", "eksst", "grow",
        ),
        bundledModemPatcherVersion = "1.1.0",
        bundledModemPatcherSha256 = "e504538ef8490b8bfe149c2269f8eacb2dfa4e72679c51c8fb8a652dc5ae31cc",
    )

    private fun openImageEntry(): ZipInputStream {
        val zip = openZip()
        try {
            while (true) {
                val entry = zip.nextEntry ?: error("image.img.gz is absent")
                if (entry.name == IMAGE) {
                    return zip
                }
            }
        } catch (failure: Throwable) {
            zip.close()
            throw failure
        }
    }

    private fun openZip(): ZipInputStream {
        val input = requireNotNull(resolver.openInputStream(uri)) { "Cannot open selected image" }
        return ZipInputStream(BufferedInputStream(input, BUFFER_SIZE))
    }

    private fun InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit) { "Manifest is too large" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }

    private class CountingDigestInputStream(input: InputStream, digest: MessageDigest) :
        FilterInputStream(DigestInputStream(input, digest)) {
        var count: Long = 0
            private set

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) count++
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val value = super.read(buffer, offset, length)
            if (value > 0) count += value
            return value
        }
    }

    companion object {
        private const val MANIFEST = "manifest.json"
        private const val IMAGE = "image.img.gz"
        private const val BUFFER_SIZE = 1024 * 1024
        private const val MAX_MANIFEST_BYTES = 64 * 1024
        private const val ZERO_SHA256 = "0000000000000000000000000000000000000000000000000000000000000000"
    }
}
