package com.dc2f.git

import com.dc2f.util.*
import mu.KotlinLogging
import java.nio.file.*
import java.time.ZonedDateTime
import java.time.format.*
import java.time.temporal.ChronoField

//data class CommitInfo(val authorName: String, val authorEmail: String, val authorDate: ZonedDateTime)

private val logger = KotlinLogging.logger {}

class GitInfoLoaderCmd(
    val path: Path
) {

    fun load(): Map<String, CommitInfo> =
        Timing("GitInfoLoader").measure {
            _load()
        }

    private fun _load(): Map<String, CommitInfo> {
        val absPath = path.toAbsolutePath()

        val workDirRoot = generateSequence(absPath) { path ->
            Files.exists(path.resolve(".git")).not().then {
                path.parent ?: throw IllegalArgumentException("Unable to find '.git' directory at $absPath")
            }
        }.last()
        val relativePrefix = workDirRoot.relativize(absPath).toString() + "/"
        logger.debug("relative prefix: $relativePrefix")
        val p = ProcessBuilder(
            "git",
            "-C",
            absPath.toString(),
            "log",
            "--name-only",
            "--no-merges",
            "--format=format:%x1e%H%x1f%h%x1f%s%x1f%aN%x1f%aE%x1f%ai",
            "HEAD"
        ).start()
        val fmt = DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_DATE)
            .appendLiteral(' ')
            .appendValue(ChronoField.HOUR_OF_DAY, 2)
            .appendLiteral(':')
            .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
            .appendLiteral(':')
            .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
            .appendLiteral(' ')
            .appendOffset("+HHMM", "Z")
            .toFormatter()
        p.waitFor()
        val text = p.inputStream.reader().readText()
            .trim('\n', '\u001e', '\'')
        return text.splitToSequence('\u001e').map { commit ->
            val lines = commit.split('\n')
            val fields = lines[0].split('\u001f')
            logger.debug { "Hash: ${fields[0]}" }
            val date = ZonedDateTime.from(fmt.parse(fields[5]))

            val commitInfo = CommitInfo(fields[3], fields[4], date)
            lines.drop(1)
                .filter { it.startsWith(relativePrefix) }
                .map { fileName ->
                    fileName.substring(relativePrefix.length) to commitInfo
                }
        }.flatten().toMap()
    }
}