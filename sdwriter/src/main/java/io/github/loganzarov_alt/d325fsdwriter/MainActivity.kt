// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
// Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
package io.github.loganzarov_alt.d325fsdwriter

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var chooseButton: Button
    private lateinit var verifyButton: Button
    private lateinit var targetsButton: Button
    private lateinit var writeButton: Button
    private lateinit var manifestView: TextView
    private lateinit var targetSpinner: Spinner
    private lateinit var confirmation: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var statusView: TextView

    private var selectedUri: Uri? = null
    private var selectedManifest: ImageManifest? = null
    private var verifiedUri: Uri? = null
    private var targets: List<BlockTarget> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "D325 SD Writer"
        window.statusBarColor = Color.rgb(11, 19, 32)
        window.navigationBarColor = Color.rgb(11, 19, 32)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 48)
            setBackgroundColor(Color.rgb(16, 27, 44))
        }
        content.addView(TextView(this).apply {
            textSize = 22f
            setTextColor(Color.rgb(67, 229, 196))
            text = "D325f SDR  //  SD Writer"
        })
        content.addView(TextView(this).apply {
            setTextColor(Color.rgb(237, 245, 250))
            text = "Writes the separate verified .img (or compact .d325img), expands userdata and creates clean ext4. ROOT IS REQUIRED. Pixel 4 needs a USB-OTG card reader. Internal storage is always rejected."
            setPadding(0, 12, 0, 20)
        })

        chooseButton = Button(this).apply {
            text = "1. Select separate .img"
            styleButton(this)
            setOnClickListener { chooseImage() }
        }
        content.addView(chooseButton)

        manifestView = TextView(this).apply {
            text = "No image selected"
            setTextColor(Color.rgb(237, 245, 250))
            setPadding(0, 12, 0, 12)
        }
        content.addView(manifestView)

        verifyButton = Button(this).apply {
            text = "2. Verify full SHA-256"
            styleButton(this)
            isEnabled = false
            setOnClickListener { verifyImage() }
        }
        content.addView(verifyButton)

        targetsButton = Button(this).apply {
            text = "3. Find SD / USB targets"
            styleButton(this)
            isEnabled = false
            setOnClickListener { refreshTargets() }
        }
        content.addView(targetsButton)

        targetSpinner = Spinner(this)
        content.addView(targetSpinner)

        confirmation = EditText(this).apply {
            hint = "Type ERASE mmcblk1 (use the selected name)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(148, 163, 184))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        content.addView(confirmation)

        writeButton = Button(this).apply {
            text = "4. ERASE SD, write, expand userdata"
            styleButton(this)
            isEnabled = false
            setOnClickListener { writeImage() }
        }
        content.addView(writeButton)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 10_000
            progress = 0
            visibility = View.INVISIBLE
        }
        content.addView(progressBar)

        statusView = TextView(this).apply {
            text = "Idle"
            setTextColor(Color.rgb(237, 245, 250))
            setPadding(0, 12, 0, 0)
            setTextIsSelectable(true)
        }
        content.addView(statusView)
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Legacy Activity result API keeps the writer dependency-free")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMAGE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // The open descriptor remains usable for this Activity even if a provider is not persistable.
        }
        selectedUri = uri
        verifiedUri = null
        targets = emptyList()
        confirmation.setText("")
        runTask("Reading image manifest…") {
            val archive = ImageArchive(contentResolver, uri)
            val manifest = archive.readManifest()
            runOnUiThread {
                selectedManifest = manifest
                manifestView.text = buildString {
                    appendLine("Model: ${manifest.model}; source: ${archive.sourceLabel}")
                    appendLine("Compact raw image: ${formatBytes(manifest.rawSize)}")
                    appendLine("Minimum SD: ${formatBytes(manifest.minimumTargetBytes)}")
                    appendLine("userdata starts at sector ${manifest.userdataStartSector} and will fill the remaining card")
                    appendLine("Modem Patcher: ${manifest.bundledModemPatcherVersion} (${manifest.bundledModemPatcherSha256.take(12)}…)")
                    append("Sanitized: ${manifest.sanitizedPartitions.joinToString()}")
                }
                verifyButton.isEnabled = true
                targetsButton.isEnabled = false
                writeButton.isEnabled = false
                statusView.text = "Image identity recognized. All bytes still need SHA-256 verification."
            }
        }
    }

    private fun chooseImage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/octet-stream", "application/x-raw-disk-image", "application/zip"),
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_IMAGE)
    }

    private fun verifyImage() {
        val uri = selectedUri ?: return
        runTask("Verifying the entire image before any SD write…") {
            val verified = ImageArchive(contentResolver, uri).verify(::showProgress)
            runOnUiThread {
                selectedManifest = verified.manifest
                verifiedUri = uri
                targetsButton.isEnabled = true
                writeButton.isEnabled = targets.isNotEmpty()
                statusView.text = "Image verified: SHA-256 exactly matches the published sanitized D325 image. No device was changed."
            }
        }
    }

    private fun refreshTargets() {
        val manifest = selectedManifest ?: return
        require(verifiedUri == selectedUri)
        runTask("Requesting root and checking removable devices…") {
            DeviceDiscovery.requireRootAndTools()
            val found = DeviceDiscovery.discover(manifest.minimumTargetBytes)
            runOnUiThread {
                targets = found
                targetSpinner.adapter = ArrayAdapter(
                    this,
                    android.R.layout.simple_spinner_dropdown_item,
                    found,
                )
                writeButton.isEnabled = found.isNotEmpty()
                statusView.text = if (found.isEmpty()) {
                    "No removable non-internal SD device large enough for this image was found."
                } else {
                    "Found ${found.size} removable target(s). Select one and type ERASE plus its base name. Mounted target partitions are unmounted only after confirmation."
                }
            }
        }
    }

    private fun writeImage() {
        val uri = selectedUri ?: return
        val manifest = selectedManifest ?: return
        require(verifiedUri == uri) { "Image must be verified first" }
        val index = targetSpinner.selectedItemPosition
        require(index in targets.indices) { "Select a target" }
        val target = targets[index]
        val expected = "ERASE ${target.baseName}"
        if (confirmation.text.toString().trim() != expected) {
            statusView.text = "Confirmation mismatch. Type exactly: $expected"
            return
        }
        targets = emptyList()
        confirmation.setText("")
        runTask("Starting destructive write to ${target.devicePath}…") {
            CardWriter(
                archive = ImageArchive(contentResolver, uri),
                manifest = manifest,
                target = target,
                status = ::showStatus,
                progress = ::showProgress,
            ).writeAndExpand()
            runOnUiThread {
                statusView.text = "SUCCESS: ${target.devicePath} contains the sanitized D325 image; userdata was recreated across the remaining SD space. Safely remove the card."
                writeButton.isEnabled = false
            }
        }
    }

    private fun runTask(initialStatus: String, task: () -> Unit) {
        setBusy(true, initialStatus)
        worker.submit {
            try {
                task()
            } catch (failure: Throwable) {
                runOnUiThread {
                    statusView.text = "ERROR: ${failure.message ?: failure.javaClass.simpleName}"
                }
            } finally {
                runOnUiThread {
                    progressBar.visibility = View.INVISIBLE
                    chooseButton.isEnabled = true
                    verifyButton.isEnabled = selectedManifest != null
                    targetsButton.isEnabled = verifiedUri == selectedUri
                    if (verifiedUri == selectedUri && targets.isNotEmpty()) writeButton.isEnabled = true
                }
            }
        }
    }

    private fun setBusy(busy: Boolean, text: String) {
        chooseButton.isEnabled = !busy
        verifyButton.isEnabled = !busy && selectedManifest != null
        targetsButton.isEnabled = !busy && verifiedUri == selectedUri
        writeButton.isEnabled = false
        progressBar.progress = 0
        progressBar.visibility = if (busy) View.VISIBLE else View.INVISIBLE
        statusView.text = text
    }

    private fun showStatus(text: String) = runOnUiThread { statusView.text = text }

    private fun showProgress(done: Long, total: Long) = runOnUiThread {
        progressBar.visibility = View.VISIBLE
        progressBar.progress = if (total > 0) ((done * 10_000L) / total).toInt() else 0
        statusView.text = "${formatBytes(done)} / ${formatBytes(total)}"
    }

    private fun formatBytes(value: Long): String = "%.2f GiB".format(value / 1024.0 / 1024.0 / 1024.0)

    private fun styleButton(button: Button) {
        button.backgroundTintList = ColorStateList.valueOf(Color.rgb(67, 229, 196))
        button.setTextColor(Color.rgb(11, 19, 32))
    }

    companion object {
        private const val REQUEST_IMAGE = 325
    }
}
