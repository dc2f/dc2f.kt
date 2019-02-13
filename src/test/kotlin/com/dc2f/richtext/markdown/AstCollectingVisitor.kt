package com.dc2f.richtext.markdown

// copied from
// https://github.com/vsch/flexmark-java/blob/77d07b1869d2032ab7994955fed46a864f46893f/flexmark-test-util/src/main/java/com/vladsch/flexmark/test/AstCollectingVisitor.java

import com.vladsch.flexmark.util.ast.*


class AstCollectingVisitor : NodeVisitorBase() {
    protected var output = StringBuilder()
    protected var indent = 0
    protected var eolPending = false

    val ast: String
        get() = output.toString()

    fun clear() {
        output = StringBuilder()
        indent = 0
        eolPending = false
    }

    protected fun appendIndent() {
        for (i in 0 until indent * 2) {
            output.append(' ')
        }
        eolPending = true
    }

    protected fun appendEOL() {
        output.append(EOL)
        eolPending = false
    }

    protected fun appendPendingEOL() {
        if (eolPending) appendEOL()
    }

    fun collectAndGetAstText(node: Node): String {
        visit(node)
        return ast
    }

    fun collect(node: Node) {
        visit(node)
    }

    protected override fun visit(node: Node) {
        appendIndent()
        node.astString(output, true)
        output.append(EOL)
        indent++

        try {
            super.visitChildren(node)
        } finally {
            indent--
        }
    }

    companion object {
        val EOL = "\n"
    }
}