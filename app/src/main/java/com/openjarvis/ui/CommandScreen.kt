package com.openjarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openjarvis.agent.AgentCore
import com.openjarvis.agent.AgentState
import com.openjarvis.ui.theme.VoidColor

@Composable
fun CommandScreen(
    agentCore: AgentCore,
    onBack: () -> Unit
) {
    var command by remember { mutableStateOf("") }
    var lastCommand by remember { mutableStateOf("") }
    var sourcePath by remember { mutableStateOf("app/src/main/java/com/openjarvis/") }
    var sourceCode by remember { mutableStateOf("") }
    var issueDescription by remember { mutableStateOf("") }
    val selfCodeProposal by agentCore.selfCodeProposal.collectAsState()
    val selfCodeStatus by agentCore.selfCodeStatus.collectAsState()
    val agentState by agentCore.state.collectAsState()
    val isRunning = agentState is AgentState.Running

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VoidColor.Void950)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("← Back") }
            Spacer(Modifier.width(8.dp))
            Text(
                "JARVIS COMMAND",
                color = VoidColor.TextPrimary,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "What should I do?",
            color = VoidColor.TextPrimary,
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            "Give Jarvis a simple command in natural language.",
            color = VoidColor.TextDisabled
        )

        Spacer(Modifier.height(20.dp))

        if (lastCommand.isNotBlank()) {
            Surface(
                color = VoidColor.Void800,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("YOUR COMMAND", color = VoidColor.Violet, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(lastCommand, color = VoidColor.TextPrimary)
                    Spacer(Modifier.height(16.dp))
                    Text("JARVIS STATUS", color = VoidColor.Violet, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    val status = when (val state = agentState) {
                        is AgentState.Idle -> "Ready"
                        is AgentState.Running -> state.step
                        is AgentState.Done -> state.result
                        is AgentState.Error -> "Error: ${state.message}"
                    }
                    Text(status, color = if (agentState is AgentState.Error) VoidColor.Red else VoidColor.TextSecondary)
                }
            }
        } else {
            Text(
                "Try: “Open YouTube” or “Open WhatsApp”.",
                color = VoidColor.TextSecondary
            )
        }

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = command,
            onValueChange = { command = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isRunning,
            placeholder = { Text("Type a command…") },
            minLines = 2,
            maxLines = 4
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                val text = command.trim()
                if (text.isNotEmpty()) {
                    lastCommand = text
                    command = ""
                    agentCore.executeTask(text)
                }
            },
            enabled = command.isNotBlank() && !isRunning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isRunning) "Working…" else "Send command")
        }

        Spacer(Modifier.height(28.dp))
        Divider(color = VoidColor.Void800)
        Spacer(Modifier.height(16.dp))
        Text("SELF-CODE WORKSPACE", color = VoidColor.Violet, fontWeight = FontWeight.Bold)
        Text(
            "Give Jarvis a source file and describe its bug. It will create a reviewable replacement proposal, not modify the installed app.",
            color = VoidColor.TextSecondary
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = sourcePath,
            onValueChange = { sourcePath = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Source file path") },
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = issueDescription,
            onValueChange = { issueDescription = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Describe the error") },
            minLines = 2,
            maxLines = 4
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = sourceCode,
            onValueChange = { sourceCode = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Paste current source code") },
            minLines = 5,
            maxLines = 10
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { agentCore.proposeCodeFix(sourcePath.trim(), sourceCode, issueDescription.trim()) },
            enabled = sourcePath.isNotBlank() && sourceCode.isNotBlank() &&
                issueDescription.isNotBlank() && !selfCodeStatus.contains("Analyzing"),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Generate code-fix proposal") }
        Spacer(Modifier.height(8.dp))
        Text(selfCodeStatus, color = VoidColor.TextSecondary)
        selfCodeProposal?.let { proposal ->
            Spacer(Modifier.height(12.dp))
            Surface(
                color = VoidColor.Void800,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("PROPOSAL REVIEW", color = VoidColor.Violet, fontWeight = FontWeight.Bold)
                    Text("File: ${proposal.relativePath}", color = VoidColor.TextPrimary)
                    Text("SHA-256: ${proposal.sha256}", color = VoidColor.TextDisabled)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = proposal.content,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Proposed replacement (read-only)") },
                        minLines = 6,
                        maxLines = 12
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Approval exports this file to Jarvis's private workspace only. It does not apply or run the code.",
                        color = VoidColor.TextSecondary
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            agentCore.approveSelfCodeProposal(proposal.id).onSuccess {
                                sourceCode = ""
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Approve & export privately") }
                    TextButton(
                        onClick = { agentCore.rejectSelfCodeProposal(proposal.id) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Reject proposal") }
                }
            }
        }
    }
}
