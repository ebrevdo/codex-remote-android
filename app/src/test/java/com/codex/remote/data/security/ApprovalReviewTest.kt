package com.codex.remote.data.security

import com.codex.remote.domain.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ApprovalReviewTest {
    private fun permission(raw: String) = ApprovalRequest(
        "p1", ApprovalKind.PERMISSION, "Permission", "Reason",
        "item/permissions/requestApproval", raw,
    )

    @Test fun permissionShowsExactPathsAndGrantsOnlyThisTurn() {
        val request = permission("""{"cwd":"/work","permissions":{"fileSystem":{"read":["/etc"],"write":["/work/result"]},"network":{"enabled":true}}}""")
        val review = reviewApproval(request)
        assertTrue(review.canApprove)
        assertTrue(review.details.contains("/work/result"))
        assertTrue(review.details.contains("unrestricted network"))
        val response = approvalResult(request, "accept", emptyMap())
        assertEquals("turn", response.getValue("scope").jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement(request.rawParams).jsonObject["permissions"], response["permissions"])
        assertEquals(JsonObject(emptyMap()), approvalResult(request, "decline", emptyMap())["permissions"])
    }

    @Test fun unknownPermissionScopesAndWrongTypesFailClosed() {
        listOf(
            """{"cwd":"/work","permissions":{"futureScope":true}}""",
            """{"cwd":"/work","permissions":{"network":{"enabled":"true"}}}""",
            """{"cwd":"/work","permissions":{"fileSystem":{"entries":[{"path":{"type":"special","value":{"kind":"unknown","path":"/"}},"access":"write"}]}}}""",
            """{"cwd":"/work","permissions":{"network":{"enabled":true}},"futureGrant":true}""",
        ).forEach {
            val request = permission(it)
            assertFalse(reviewApproval(request).canApprove)
            assertThrows(IllegalArgumentException::class.java) { approvalResult(request, "accept", emptyMap()) }
        }
    }

    @Test fun commandRequiresCwdAndSessionDecisionIsUnsupported() {
        val request = ApprovalRequest("c1", ApprovalKind.COMMAND, "", "", "item/commandExecution/requestApproval",
            """{"command":"git status","cwd":"/work"}""")
        assertTrue(reviewApproval(request).canApprove)
        assertFalse(reviewApproval(request.copy(rawParams = """{"command":"git status"}""")).canApprove)
        assertThrows(IllegalArgumentException::class.java) { approvalResult(request, "acceptForSession", emptyMap()) }
    }

    @Test fun fileChangeNeedsMatchingDiffAndCannotGrantPersistentWriteRoot() {
        val request = ApprovalRequest("f1", ApprovalKind.FILE_CHANGE, "", "", "item/fileChange/requestApproval",
            """{"threadId":"t","turnId":"u","itemId":"f"}""")
        assertFalse(reviewApproval(request).canApprove)
        val withDiff = request.copy(rawFileChanges = """[{"path":"/work/file","kind":{"type":"update","move_path":"/work/renamed"},"diff":"-old\\n+new"}]""")
        assertTrue(reviewApproval(withDiff).canApprove)
        assertTrue(reviewApproval(withDiff).details.contains("-old"))
        assertTrue(reviewApproval(withDiff).details.contains("/work/renamed"))
        assertFalse(reviewApproval(withDiff.copy(rawFileChanges = """[{"path":"/work/file","kind":{"type":"future"},"diff":"x"}]""")).canApprove)
        assertFalse(reviewApproval(withDiff.copy(rawParams = """{"grantRoot":"/"}""")).canApprove)
    }

    @Test fun rejectingUserInputNeverSendsPreselectedAnswers() {
        val request = ApprovalRequest("u1", ApprovalKind.USER_INPUT, "", "", "item/tool/requestUserInput",
            questions = listOf(ApprovalQuestion("q", "", "Choose", listOf("yes"))))
        val result = approvalResult(request, "decline", mapOf("q" to listOf("yes")))
        assertEquals(JsonObject(emptyMap()), result["answers"])
    }

    @Test fun oversizedUnknownAndBidiRequestsCannotMisleadReview() {
        val unknown = ApprovalRequest("x", ApprovalKind.UNKNOWN, "", "", "future/request")
        assertFalse(reviewApproval(unknown).canApprove)
        assertFalse(reviewApproval(permission(" ".repeat(MAX_APPROVAL_CHARS + 1))).canApprove)
        assertEquals("abc\\u202edef", visibleControls("abc" + 0x202e.toChar() + "def"))
    }
}
