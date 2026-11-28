package com.openjarvis.builder

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Restricted workspace for Jarvis-authored source changes.
 *
 * This workspace is deliberately separate from the installed APK and the app's
 * executable source. Files written here are proposals only; Android cannot hot
 * swap Kotlin code into the running APK.
 */
class SelfCodeWorkspace(context: Context) {

    private val root = File(context.filesDir, "self_code_workspace").canonicalFile
    private val proposals = File(root, "proposals")
    private val backups = File(root, "backups")
    private val audit = File(root, "audit.log")

    init {
        proposals.mkdirs()
        backups.mkdirs()
    }

    data class Proposal(
        val id: String,
        val relativePath: String,
        val content: String,
        val sha256: String,
        val createdAt: Long
    )

    data class WorkspaceStatus(
        val proposalCount: Int,
        val backupCount: Int,
        val auditLog: String
    )

    /**
     * Only source/text files inside the private proposal workspace are writable.
     * Build scripts, manifests, permissions, signing files, secrets and runtime
     * configuration are intentionally excluded.
     */
    fun createProposal(relativePath: String, content: String): Proposal {
        require(content.isNotBlank()) { "Proposed code cannot be empty" }
        require(content.length <= MAX_CONTENT_CHARS) { "Proposal is too large" }

        val safePath = validateRelativePath(relativePath)
        val id = UUID.randomUUID().toString()
        val proposalDir = File(proposals, id).canonicalFile
        require(proposalDir.path.startsWith(proposals.canonicalPath + File.separator))
        proposalDir.mkdirs()

        val destination = File(proposalDir, safePath).canonicalFile
        require(destination.path.startsWith(proposalDir.path + File.separator))
        destination.parentFile?.mkdirs()
        destination.writeText(content, Charsets.UTF_8)

        val proposal = Proposal(
            id = id,
            relativePath = safePath,
            content = content,
            sha256 = sha256(content),
            createdAt = System.currentTimeMillis()
        )
        appendAudit("PROPOSAL_CREATED id=$id path=$safePath sha256=${proposal.sha256}")
        return proposal
    }

    fun readProposal(id: String): Proposal {
        require(ID_PATTERN.matches(id)) { "Invalid proposal ID" }
        val dir = File(proposals, id).canonicalFile
        require(dir.path.startsWith(proposals.canonicalPath + File.separator))
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        require(files.size == 1) { "Proposal is missing or malformed" }
        val file = files.single()
        val relative = file.relativeTo(dir).invariantSeparatorsPath
        validateRelativePath(relative)
        val content = file.readText(Charsets.UTF_8)
        return Proposal(id, relative, content, sha256(content), dir.lastModified())
    }

    /**
     * Export is a separate, explicit approval step. It copies a proposal to
     * app-private cache for a later user-controlled review/export flow.
     * It never overwrites installed app code or runs the proposed code.
     */
    fun approveAndExport(id: String, userApproved: Boolean): File {
        require(userApproved) { "User approval is required before exporting code" }
        val proposal = readProposal(id)
        val exportDir = File(root, "approved_exports").canonicalFile
        exportDir.mkdirs()
        val output = File(exportDir, "${id}_${File(proposal.relativePath).name}").canonicalFile
        require(output.path.startsWith(exportDir.path + File.separator))

        val backup = File(backups, "${id}.bak")
        if (output.exists()) output.copyTo(backup, overwrite = true)
        File(output.parentFile, output.name).writeText(proposal.content, Charsets.UTF_8)

        appendAudit("USER_APPROVED_EXPORT id=$id path=${proposal.relativePath}")
        return output
    }

    fun status(): WorkspaceStatus {
        val proposalCount = proposals.listFiles()?.count { it.isDirectory } ?: 0
        val backupCount = backups.listFiles()?.count { it.isFile } ?: 0
        return WorkspaceStatus(
            proposalCount = proposalCount,
            backupCount = backupCount,
            auditLog = if (audit.exists()) audit.readText(Charsets.UTF_8).takeLast(MAX_AUDIT_CHARS) else ""
        )
    }

    private fun validateRelativePath(path: String): String {
        val normalized = path.replace('\\\\', '/').trim()
        require(normalized.isNotBlank()) { "File path is required" }
        require(!normalized.startsWith("/") && !normalized.contains(":")) { "Absolute paths are not allowed" }
        val segments = normalized.split('/')
        require(segments.none { it.isBlank() || it == "." || it == ".." }) { "Unsafe path" }
        val extension = segments.last().substringAfterLast('.', "").lowercase(Locale.ROOT)
        require(extension in ALLOWED_EXTENSIONS) { "File type is not allowed" }
        val lower = normalized.lowercase(Locale.ROOT)
        require(BLOCKED_PATH_PARTS.none { part -> lower.contains(part) }) { "Protected project file" }
        return segments.joinToString("/")
    }

    private fun appendAudit(message: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())
        audit.appendText("$stamp $message\\n", Charsets.UTF_8)
        if (audit.length() > MAX_AUDIT_BYTES) {
            val tail = audit.readLines().takeLast(500)
            audit.writeText(tail.joinToString("\\n", postfix = "\\n"), Charsets.UTF_8)
        }
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MAX_CONTENT_CHARS = 200_000
        private const val MAX_AUDIT_CHARS = 20_000
        private const val MAX_AUDIT_BYTES = 64 * 1024L
        private val ID_PATTERN = Regex("^[a-f0-9-]{36}$")
        private val ALLOWED_EXTENSIONS = setOf("kt", "java", "md", "txt", "json")
        private val BLOCKED_PATH_PARTS = listOf(
            "manifest", "build.gradle", "settings.gradle", "gradle.properties",
            "local.properties", "proguard", "keystore", "secret", "credential",
            "apikey", "api_key", "token", "permission", "accessibility"
        )
    }
}
