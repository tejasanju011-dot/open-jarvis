package com.openjarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
    val agentState by agentCore.state.collectAsState()
    val isRunning = agentState is AgentState.Running

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VoidColor.Void950)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(20.dp)
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

        Spacer(Modifier.weight(1f))

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
    }
}
