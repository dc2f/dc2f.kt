package com.dc2f.git

import com.dc2f.util.*
import com.sun.xml.internal.messaging.saaj.util.ByteOutputStream
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.nio.file.Path
import java.time.ZonedDateTime


data class CommitInfo(val authorName: String, val authorEmail: String, val authorDate: ZonedDateTime)

class GitInfoLoader(
    val path: Path
) {

    fun load(): Map<String, CommitInfo> =
        Timing("GitInfoLoader").measure {
            _load()
        }

    private fun _load(): Map<String, CommitInfo> {
        val file = path.toFile().absoluteFile
        println("trying $file")
        val repositoryBuilder = FileRepositoryBuilder().apply {
//            gitDir = file
            findGitDir(file)
        }
        val baseDir = repositoryBuilder.gitDir
        return repositoryBuilder.build().use { repository ->
            DiffFormatter(ByteOutputStream()).use { fmt ->
                fmt.setRepository(repository)
                val commits = Git(repository).log().call()

                val relativeDir = file.relativeTo(baseDir.parentFile)
                println("relative dir ${relativeDir}")


                commits.flatMap { commit ->
                    val a = if (commit.parentCount > 0) {
                        commit.getParent(0).tree
                    } else {
                        null
                    }
                    val b = commit.tree
                    val diff = fmt.scan(a, b)

                    val date = ZonedDateTime.ofInstant(commit.authorIdent.`when`.toInstant(), commit.authorIdent.timeZone.toZoneId())

                    println("revision ${commit.id} at ${commit.commitTime} by ${commit.authorIdent}:")

                    val commitInfo = CommitInfo(commit.authorIdent.name, commit.authorIdent.emailAddress, date)

                    diff.mapNotNull {  entry ->
                        entry.newPath.startsWith(relativeDir.toString()).then {
                            //                    println("   ${entry.newPath}")
                            entry.newPath to commitInfo
                        }
                    }
                }.reversed().toMap()
            }



        }


        /*
        val head = repository.resolve("HEAD")
        val walk = RevWalk(repository)
        val tree = walk.parseTree(head)
        val treeWalker = TreeWalk(repository)
        treeWalker.isRecursive = true
        treeWalker.addTree(tree)
        while (treeWalker.next()) {
            treeWalker.pathString
            val oid = treeWalker.getObjectId(0)
            val fileMode = treeWalker.fileMode
        }
        */
    }
}