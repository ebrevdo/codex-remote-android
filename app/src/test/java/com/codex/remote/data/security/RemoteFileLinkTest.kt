package com.codex.remote.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteFileLinkTest {
    @Test fun resolvesTheReportReferenceAndSourceLocations() {
        assertEquals(RemoteFileLink("/repo/README.md", 20), parseRemoteFileLink("README.md:20", "/repo"))
        val path = "/root/code/koromon/benchmarks/results/report.md"
        for (target in listOf("$path:20", "$path:20:7", "$path#L20", "$path#L20C7", "$path#L20-L25")) {
            assertEquals(RemoteFileLink(path, 20), parseRemoteFileLink(target, "/other"))
        }
    }

    @Test fun resolvesNestedLinksAgainstTheDocumentDirectory() {
        val first = parseRemoteFileLink("reports/first.md", "/repo")!!
        assertEquals(RemoteFileLink("/repo/reports/next.md"), parseRemoteFileLink("./next.md", first.directory))
        assertEquals(RemoteFileLink("/repo/README.md"), parseRemoteFileLink("../README.md#heading", first.directory))
        assertEquals(RemoteFileLink("/README.md"), parseRemoteFileLink("../../README.md", "/repo"))
    }

    @Test fun preservesFilenameCharactersWithoutShellInterpretation() {
        val name = "report + notes; ${'$'}(whoami) 'draft'.md"
        assertEquals(RemoteFileLink("/repo/$name"), parseRemoteFileLink(name, "/repo"))
        assertEquals(RemoteFileLink("/repo/a b+c#d:20.md"), parseRemoteFileLink("a%20b+c%23d%3A20.md", "/repo"))
        assertEquals(RemoteFileLink("/repo/数学.md"), parseRemoteFileLink("数学.md", "/repo"))
    }

    @Test fun acceptsLocalFileUrisAndWindowsPaths() {
        assertEquals(RemoteFileLink("/repo/report.md", 20), parseRemoteFileLink("file:///repo/report.md#L20", ""))
        assertEquals(RemoteFileLink("C:/repo/report.md", 20), parseRemoteFileLink("""C:\repo\report.md:20""", ""))
        assertEquals(RemoteFileLink("C:/report.md"), parseRemoteFileLink("file:///C:/report.md", ""))
        val file = RemoteFileLink("C:/first.md")
        assertEquals(RemoteFileLink("C:/next.md"), parseRemoteFileLink("next.md", file.directory))
    }

    @Test fun neverTreatsBrowserOrCustomSchemesAsFiles() {
        for (target in listOf("https://example.com/report.md", "https://example.com:443", "javascript:20", "http://host/a", "javascript:alert(1)",
            "intent://file", "data:text/plain,secret", "mailto:user@example.com", "//host/file", "file://host/path",
            """\\host\share\file""", "file:relative.md", "file:///a?query", "#heading", "~/file")) {
            assertNull(target, parseRemoteFileLink(target, "/repo"))
        }
    }

    @Test fun rejectsMalformedUnboundedAndAmbiguousReferences() {
        for (target in listOf("", " ", "a\u0000b", "a%00b", "a%0Ab", "%ZZ", "%2F%2Fhost/file",
            "a:0", "a:999999999999999999999", "a".repeat(8193))) {
            assertNull(target, parseRemoteFileLink(target, "/repo"))
        }
        for (base in listOf("", "//host/share", "/repo\u0000", "/" + "a".repeat(8192))) {
            assertNull(parseRemoteFileLink("relative.md", base))
        }
        assertNull(parseRemoteFileLink("a".repeat(8192), "/repo"))
    }
}
