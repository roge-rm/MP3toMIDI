package com.rm.mp3tomidi.util

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelProviderTest {

    @JvmField
    @Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: HttpServer
    private val content = "fake model bytes, doesn't need to look like a real onnx file".toByteArray()
    private val contentSha256 = sha256(content)
    private val slowContent = ByteArray(5_000_000) { 1 }

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "http://localhost:${server.address.port}/model.bin")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/model.bin") { exchange ->
            exchange.sendResponseHeaders(200, content.size.toLong())
            exchange.responseBody.use { it.write(content) }
        }
        server.createContext("/wrong.bin") { exchange ->
            val wrong = "this is not the expected content".toByteArray()
            exchange.sendResponseHeaders(200, wrong.size.toLong())
            exchange.responseBody.use { it.write(wrong) }
        }
        server.createContext("/slow.bin") { exchange ->
            // Trickled out in small, paced chunks so a test can cancel mid-transfer with room to
            // spare, rather than racing a fast in-memory copy that might finish before cancel()
            // takes effect.
            exchange.sendResponseHeaders(200, slowContent.size.toLong())
            exchange.responseBody.use { out ->
                var offset = 0
                while (offset < slowContent.size) {
                    val len = minOf(4096, slowContent.size - offset)
                    out.write(slowContent, offset, len)
                    out.flush()
                    Thread.sleep(5)
                    offset += len
                }
            }
        }
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun `downloads through a redirect and verifies checksum`() = runBlocking {
        val spec = ModelSpec(
            fileName = "model.bin",
            downloadUrl = "http://localhost:${server.address.port}/redirect",
            sha256 = contentSha256,
        )
        val progressUpdates = mutableListOf<Float>()

        val file = ModelProvider.ensureAvailable(tempFolder.root, spec, { false }) { progressUpdates += it }

        assertTrue(file.exists())
        assertEquals(content.toList(), file.readBytes().toList())
        assertEquals(1f, progressUpdates.last())
    }

    @Test
    fun `an already-downloaded file with a matching checksum is not re-downloaded`() = runBlocking {
        val spec = ModelSpec(
            fileName = "model.bin",
            downloadUrl = "http://localhost:${server.address.port}/model.bin",
            sha256 = contentSha256,
        )
        val existing = tempFolder.newFile(spec.fileName)
        existing.writeBytes(content)

        var progressCalls = 0
        val file = ModelProvider.ensureAvailable(tempFolder.root, spec, { false }) { progressCalls++ }

        assertEquals(existing, file)
        assertEquals(0, progressCalls)
    }

    @Test
    fun `checksum mismatch throws and leaves no file at the destination`() = runBlocking {
        val spec = ModelSpec(
            fileName = "model.bin",
            downloadUrl = "http://localhost:${server.address.port}/wrong.bin",
            sha256 = contentSha256,
        )

        try {
            ModelProvider.ensureAvailable(tempFolder.root, spec, { false }) {}
            fail("expected a checksum mismatch to throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("checksum mismatch"))
        }

        assertTrue(!File(tempFolder.root, spec.fileName).exists())
    }

    @Test
    fun `isCancelled turning true mid-download deletes the partial file`() = runBlocking {
        // Exercises the real cancel mechanism -- ConversionWorker's isStopped flag threaded down
        // as isCancelled, polled explicitly -- rather than coroutine cancellation, which was
        // verified on-device to not reliably interrupt a running conversion (see
        // DemucsStemSeparator's doc).
        val spec = ModelSpec(
            fileName = "slow.bin",
            downloadUrl = "http://localhost:${server.address.port}/slow.bin",
            sha256 = "irrelevant -- cancelled before the checksum is ever checked",
        )
        val cancelled = AtomicBoolean(false)

        val job = launch {
            try {
                ModelProvider.ensureAvailable(tempFolder.root, spec, { cancelled.get() }) {}
                fail("expected isCancelled turning true to throw")
            } catch (e: CancellationException) {
                // expected
            }
        }
        delay(50) // well into the ~25s the paced /slow.bin response takes to fully arrive
        cancelled.set(true)
        job.join()

        assertTrue(!File(tempFolder.root, "${spec.fileName}.download").exists())
        assertTrue(!File(tempFolder.root, spec.fileName).exists())
    }

    private companion object {
        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
