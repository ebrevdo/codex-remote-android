package com.codex.remote.data.ssh

import com.codex.remote.domain.RemotePlatform
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.schmizz.sshj.SSHClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class RemoteFileUploaderTest {
    @Test fun uploadNamesCannotEscapeTheirDirectory() {
        assertEquals("report.csv", uploadFileName("../../report.csv"))
        assertEquals("report.pdf", uploadFileName("C:\\temp\\report.pdf"))
        assertEquals("file", uploadFileName(".."))
        assertEquals("file", uploadFileName("/"))
    }

    @Test fun uploadNamesWorkOnWindowsAndRemoveControlCharacters() {
        assertEquals("bad__name_.pdf", uploadFileName("bad\n\u0000name?.pdf"))
        assertEquals("_CON.txt", uploadFileName("CON.txt"))
        assertEquals("_lpt1", uploadFileName("lpt1"))
        assertEquals("document.txt", uploadFileName(" document.txt. "))
    }

    @Test fun longUnicodeNamesFitFileSystemsAndKeepTheirExtension() {
        val name = uploadFileName("報".repeat(300) + ".pdf")
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 255)
        assertTrue(name.endsWith(".pdf"))
    }

    @Test fun windowsSftpPathsBecomeAbsoluteCodexPaths() {
        assertEquals("C:/Users/me/report.pdf", uploadedPathForPlatform("/C:/Users/me/report.pdf", RemotePlatform.WINDOWS))
        assertEquals("C:/Users/me/report.pdf", uploadedPathForPlatform("C:/Users/me/report.pdf", RemotePlatform.WINDOWS))
        assertEquals("/C:/report.pdf", uploadedPathForPlatform("/C:/report.pdf", RemotePlatform.POSIX))
        assertEquals("/home/me/report.pdf", uploadedPathForPlatform("/home/me/report.pdf", RemotePlatform.POSIX))
    }

    /** Opt-in native SSH transfer test, run by tools/test_file_upload.py; no Android device. */
    @Test fun realSftpPreservesBytesPermissionsAndCleansUpFailuresAndCancellation() = runBlocking {
        val fixturePath = System.getenv("CODEX_REMOTE_UPLOAD_TEST_FIXTURE")
        assumeTrue("Run tools/test_file_upload.py for the loopback SFTP test", fixturePath != null)
        val fixture = Json.parseToJsonElement(File(fixturePath!!).readText()).jsonObject
        fun value(key: String) = fixture.getValue(key).jsonPrimitive.content
        val directory = File(value("directory")).canonicalFile
        fun uploads() = directory.listFiles().orEmpty().filter { it.name.startsWith(".codex-remote-upload-") }.toSet()
        SSHClient(androidCompatibleSshConfig()).use { ssh ->
            ssh.addHostKeyVerifier(value("fingerprint"))
            ssh.connectTimeout = 5_000
            ssh.timeout = 10_000
            ssh.connect("127.0.0.1", value("port").toInt())
            ssh.authPublickey(value("username"), ssh.loadKeys(value("privateKey")))
            withTimeout(30_000) {
                val original = ByteArray(1_000_001) { (it % 256).toByte() }
                val name = "report \$(false); 'draft'.bin"
                var progress = -1L
                val path = uploadRemoteFile(ssh, RemotePlatform.POSIX, name, ByteArrayInputStream(original)) { progress = it }
                val uploaded = File(path)
                val uploadDirectory = requireNotNull(uploaded.parentFile)
                assertEquals(directory, requireNotNull(uploadDirectory.parentFile).canonicalFile)
                assertArrayEquals(original, uploaded.readBytes())
                assertEquals(original.size.toLong(), progress)
                assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(uploaded.toPath()))
                assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(uploadDirectory.toPath()))

                val second = uploadRemoteFile(ssh, RemotePlatform.POSIX, name, ByteArrayInputStream(byteArrayOf())) {}
                assertNotEquals(path, second)
                assertEquals(0L, File(second).length())
                assertArrayEquals(original, uploaded.readBytes())

                val beforeFailure = uploads()
                val broken = object : InputStream() {
                    var first = true
                    override fun read(): Int = error("Expected buffered reads")
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (!first) throw IOException("Document provider failed")
                        first = false
                        return len
                    }
                }
                val failure = runCatching { uploadRemoteFile(ssh, RemotePlatform.POSIX, "broken.bin", broken) {} }.exceptionOrNull()
                assertTrue(failure is IOException)
                assertEquals(beforeFailure, uploads())

                val reading = CompletableDeferred<Unit>()
                val canceled = launch {
                    val blocked = object : InputStream() {
                        override fun read(): Int = error("Expected buffered reads")
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            reading.complete(Unit)
                            CountDownLatch(1).await()
                            return -1
                        }
                    }
                    uploadRemoteFile(ssh, RemotePlatform.POSIX, "canceled.bin", blocked) {}
                }
                reading.await()
                canceled.cancelAndJoin()
                assertEquals(beforeFailure, uploads())
                val afterCancel = uploadRemoteFile(ssh, RemotePlatform.POSIX, "still-connected.txt", ByteArrayInputStream("ok".toByteArray())) {}
                assertEquals("ok", File(afterCancel).readText())
            }
        }
    }
}
