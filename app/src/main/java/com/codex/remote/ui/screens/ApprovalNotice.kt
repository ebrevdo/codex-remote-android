package com.codex.remote.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codex.remote.domain.TimelineItem
import com.codex.remote.domain.TimelineKind
import com.codex.remote.ui.theme.CodexGreen

private val approvedNotice = Regex(
    """^Automatic approval review approved\s*\(risk:\s*(low|medium|high|critical),\s*authorization:\s*[^)]+\)\s*:""",
    RegexOption.IGNORE_CASE,
)

internal fun TimelineItem.automaticApprovalRisk(): String? =
    if (kind == TimelineKind.NOTICE) approvedNotice.find(body)?.groupValues?.get(1)?.lowercase() else null

internal data class ApprovalNoticeSummary(val text: String, val expandedByDefault: Boolean)

internal fun summarizeApprovalNotices(notices: List<TimelineItem>): ApprovalNoticeSummary {
    val risk = notices.mapNotNull { it.automaticApprovalRisk() }
        .maxByOrNull { listOf("low", "medium", "high", "critical").indexOf(it) }
    val title = if (notices.size == 1) "Auto-approved" else "${notices.size} auto-approvals"
    return ApprovalNoticeSummary(
        text = if (risk == null) title else "$title · ${risk.replaceFirstChar(Char::uppercase)} risk",
        expandedByDefault = risk != "low",
    )
}

@Composable
internal fun ApprovalNotice(notices: List<TimelineItem>, modifier: Modifier = Modifier) {
    val summary = remember(notices) { summarizeApprovalNotices(notices) }
    // Lazy rows leave composition while scrolling. Keep explicit choices when they return,
    // and when new approvals change the group's default risk-based expansion.
    var expandedOverride by rememberSaveable(notices.first().id) {
        mutableStateOf<Boolean?>(null)
    }
    val expanded = expandedOverride ?: summary.expandedByDefault
    val color = if (summary.expandedByDefault) MaterialTheme.colorScheme.error else CodexGreen
    Column(modifier.testTag("approval-notices-${notices.first().id}")) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp)
                .clickable(role = Role.Button) { expandedOverride = !expanded }
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Outlined.Check, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            Text(
                summary.text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,
                contentDescription = if (expanded) "Collapse approval details" else "Expand approval details",
                modifier = Modifier.size(16.dp),
            )
        }
        if (expanded) {
            val details = remember(notices) { notices.joinToString("\n\n") { it.body } }
            ScrollableTextBody(details, Modifier.padding(top = 4.dp))
        }
    }
}
