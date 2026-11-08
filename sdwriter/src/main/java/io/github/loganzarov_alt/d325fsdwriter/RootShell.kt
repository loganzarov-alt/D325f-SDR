// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
// Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
package io.github.loganzarov_alt.d325fsdwriter

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.concurrent.thread

data class ShellResult(val code: Int, val stdout: String, val stderr: String) {
    fun requireSuccess(operation: String): ShellResult {
        check(code == 0) { "$operation failed ($code): ${stderr.ifBlank { stdout }.takeLast(2000)}" }
        return this
    }
}

object RootShell {
    private const val MAX_CAPTURE = 256 * 1024

    fun run(command: String): ShellResult {
        val process = ProcessBuilder("su", "-c", command).start()
        process.outputStream.close()
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val outThread = drain(process.inputStream, stdout)
        val errThread = drain(process.errorStream, stderr)
        val code = process.waitFor()
        outThread.join()
        errThread.join()
        return ShellResult(code, stdout.toString("UTF-8"), stderr.toString("UTF-8"))
    }

    fun read(command: String, maximumBytes: Int): ByteArray {
        val process = ProcessBuilder("su", "-c", command).start()
        process.outputStream.close()
        val stderr = ByteArrayOutputStream()
        val errThread = drain(process.errorStream, stderr)
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = process.inputStream.read(buffer)
            if (count < 0) break
            require(output.size() + count <= maximumBytes) { "Root command returned too much data" }
            output.write(buffer, 0, count)
        }
        val code = process.waitFor()
        errThread.join()
        check(code == 0) { "Root read failed ($code): ${stderr.toString("UTF-8").takeLast(2000)}" }
        return output.toByteArray()
    }

    fun write(command: String, bytes: ByteArray) {
        stream(command) { output -> output.write(bytes) }.requireSuccess("Root write")
    }

    fun stream(command: String, producer: (OutputStream) -> Unit): ShellResult {
        val process = ProcessBuilder("su", "-c", command).start()
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val outThread = drain(process.inputStream, stdout)
        val errThread = drain(process.errorStream, stderr)
        var producerFailure: Throwable? = null
        try {
            process.outputStream.use(producer)
        } catch (failure: Throwable) {
            producerFailure = failure
            process.destroy()
        }
        val code = process.waitFor()
        outThread.join()
        errThread.join()
        if (producerFailure != null) throw producerFailure
        return ShellResult(code, stdout.toString("UTF-8"), stderr.toString("UTF-8"))
    }

    private fun drain(input: InputStream, output: ByteArrayOutputStream) = thread(start = true) {
        input.use {
            val buffer = ByteArray(8192)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                if (output.size() < MAX_CAPTURE) {
                    output.write(buffer, 0, minOf(count, MAX_CAPTURE - output.size()))
                }
            }
        }
    }
}
