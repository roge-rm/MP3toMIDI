package com.rm.mp3tomidi.util

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
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

        val file = ModelProvider.ensureAvailable(tempFolder.root, spec) { progressUpdates += it }

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
        val file = ModelProvider.ensureAvailable(tempFolder.root, spec) { progressCalls++ }

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
            ModelProvider.ensureAvailable(tempFolder.root, spec) {}
            fail("expected a checksum mismatch to throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("checksum mismatch"))
        }

        assertTrue(!File(tempFolder.root, spec.fileName).exists())
    }

    private companion object {
        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
