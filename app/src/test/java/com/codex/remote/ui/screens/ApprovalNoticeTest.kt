package com.codex.remote.ui.screens

import com.codex.remote.domain.TimelineItem
import com.codex.remote.domain.TimelineKind
import org.junit.Assert.*
import org.junit.Test

class ApprovalNoticeTest {
    @Test fun singleLowRiskApprovalHasACompactCollapsedSummary() {
        val notice = approved("one")
        assertEquals(ApprovalNoticeSummary("Auto-approved · Low risk", false), summarizeApprovalNotices(listOf(notice)))
    }

    @Test fun consecutiveApprovalsShareOneRowWithoutLosingMessagesOrHistoryAnchors() {
        val notices = (1..5).map { approved("approval-$it", reason = "Read build log $it; no files changed.") }
        val presentation = groupTimelineItems(notices).single()
        assertEquals("approval-group:approval-1", presentation.item.id)
        assertEquals(notices.map { it.id }, presentation.sourceItemIds)
        assertEquals(notices, presentation.approvalNotices)
        assertEquals("5 auto-approvals · Low risk", summarizeApprovalNotices(presentation.approvalNotices).text)
    }

    @Test fun appendingApprovalsKeepsTheRowIdentityAndExistingDetails() {
        val first = approved("first")
        val before = groupTimelineItems(listOf(first)).single()
        val after = groupTimelineItems(listOf(first, approved("second"))).single()
        assertEquals(before.item.id, after.item.id)
        assertEquals(first, after.approvalNotices.first())
        assertEquals(2, after.approvalNotices.size)
    }

    @Test fun denialsErrorsAndOrdinaryMessagesBreakApprovalGroupsAndStayVisible() {
        val blocked = TimelineItem("blocked", TimelineKind.NOTICE, body = "Automatic approval review denied: write not authorized")
        val failure = TimelineItem("failure", TimelineKind.ERROR, body = "Command failed")
        val message = TimelineItem("answer", TimelineKind.AGENT, body = approved("quoted").body)
        for (separator in listOf(blocked, failure, message)) {
            val presentation = groupTimelineItems(listOf(approved("first"), separator, approved("last")))
            assertEquals(3, presentation.size)
            assertEquals(separator, presentation[1].item)
            assertTrue(presentation[1].approvalNotices.isEmpty())
        }
    }

    @Test fun higherRiskApprovalsAreShownExpandedWithTheHighestRiskInTheSummary() {
        val notices = listOf(approved("low"), approved("high", "high"), approved("medium", "medium"))
        val summary = summarizeApprovalNotices(groupTimelineItems(notices).single().approvalNotices)
        assertEquals("3 auto-approvals · High risk", summary.text)
        assertTrue(summary.expandedByDefault)
        assertTrue(summarizeApprovalNotices(listOf(approved("critical", "critical"))).expandedByDefault)
    }

    @Test fun unrecognizedApprovalFormatsAndIncompleteReviewMessagesStayUncollapsed() {
        for (body in listOf(
            "Automatic approval review pending (risk: low, authorization: high): checking",
            "Automatic approval review approved (risk: unknown, authorization: high): details",
            "Automatic approval review approved: no structured risk information",
            "Warning: Automatic approval review approved (risk: low, authorization: high): quoted",
        )) {
            val notice = TimelineItem("notice", TimelineKind.NOTICE, body = body)
            assertNull(notice.automaticApprovalRisk())
            val presentation = groupTimelineItems(listOf(notice)).single()
            assertEquals(notice, presentation.item)
            assertTrue(presentation.approvalNotices.isEmpty())
        }
    }

    @Test fun matchingIsCaseInsensitiveAndRetainsTheOriginalExplanation() {
        val notice = TimelineItem("case", TimelineKind.NOTICE, body = "AUTOMATIC APPROVAL REVIEW APPROVED (risk: LOW, authorization: HIGH): untouched explanation")
        assertEquals("low", notice.automaticApprovalRisk())
        assertEquals(notice, groupTimelineItems(listOf(notice)).single().approvalNotices.single())
    }

    @Test fun commandGroupsKeepTheirOriginalBehaviorBesideApprovalGroups() {
        val commands = listOf(
            TimelineItem("cmd-1", TimelineKind.COMMAND, title = "pwd", body = "/repo", status = "completed"),
            TimelineItem("cmd-2", TimelineKind.COMMAND, title = "git status", body = "clean", status = "completed"),
        )
        val presentation = groupTimelineItems(commands + listOf(approved("one"), approved("two")))
        assertEquals(2, presentation.size)
        assertEquals("command-group:cmd-1", presentation.first().item.id)
        assertEquals(commands.map { it.id }, presentation.first().sourceItemIds)
        assertEquals(listOf("one", "two"), presentation.last().sourceItemIds)
    }

    private fun approved(id: String, risk: String = "low", reason: String = "Read build logs without modifying files.") =
        TimelineItem(id, TimelineKind.NOTICE, body = "Automatic approval review approved (risk: $risk, authorization: high): $reason")
}
