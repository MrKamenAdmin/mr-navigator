package me.brekhin.mrnavigator.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import me.brekhin.mrnavigator.api.FileChange
import me.brekhin.mrnavigator.core.HiddenFiles
import me.brekhin.mrnavigator.util.msg
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * Tree of MR files grouped by folders. Hidden files are left out (unless [showHidden]),
 * and a folder is created only for files that are shown — so a folder that contains
 * only hidden files does not appear at any depth.
 */
class ChangesTree(
    private val onOpen: (FileChange, List<FileChange>) -> Unit,
    private val onToggleViewed: (FileChange) -> Unit,
) : Tree(DefaultTreeModel(DefaultMutableTreeNode())) {
    /** Whether the user has already looked at a file (drawn dimmed with a check mark). */
    var isViewed: (FileChange) -> Boolean = { false }

    private class Dir(val name: String)
    private class File(val change: FileChange, val hidden: Boolean)

    private var shown: List<FileChange> = emptyList()

    init {
        // Explicit setters: inside a JTree subclass Kotlin resolves `cellRenderer = …` / `showsRootHandles = …`
        // to JTree's protected *fields*, bypassing the setters — the tree UI would keep the default renderer.
        setRootVisible(false)
        setShowsRootHandles(true)
        setCellRenderer(Renderer())
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && e.button == MouseEvent.BUTTON1) openSelected()
            }
        })
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) openSelected()
                if (e.keyCode == KeyEvent.VK_SPACE) {
                    ((lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? File)?.let {
                        onToggleViewed(it.change)
                        e.consume()
                    }
                }
            }
        })
    }

    /** Files in display order — the diff tab walks through them with next/previous file. */
    val shownFiles: List<FileChange> get() = shown

    fun setChanges(changes: List<FileChange>, hidden: HiddenFiles, showHidden: Boolean) {
        val visible = changes.filter { showHidden || !hidden.isHidden(it) }.sortedBy { it.displayPath }
        val root = DefaultMutableTreeNode()
        val dirs = HashMap<String, DefaultMutableTreeNode>()
        for (c in visible) {
            val parts = c.displayPath.split('/')
            var parent = root
            var path = ""
            for (dir in parts.dropLast(1)) {
                path = if (path.isEmpty()) dir else "$path/$dir"
                parent = dirs.getOrPut(path) { DefaultMutableTreeNode(Dir(dir)).also { parent.add(it) } }
            }
            parent.add(DefaultMutableTreeNode(File(c, hidden.isHidden(c))))
        }
        compact(root)
        // Order inside the tree: folders first, then files — and the diff chain follows the same order.
        shown = collectFiles(root)
        (model as DefaultTreeModel).setRoot(root)
        TreeUtil.expandAll(this)
    }

    /** New versions of the shown files (a too-large file got its hunks), keeping expansion and selection. */
    fun update(changes: List<FileChange>) {
        val byPath = changes.associateBy { it.displayPath }
        val root = model.root as? DefaultMutableTreeNode ?: return
        for (node in root.preorderEnumeration()) {
            val n = node as DefaultMutableTreeNode
            val f = n.userObject as? File ?: continue
            byPath[f.change.displayPath]?.takeIf { it != f.change }?.let { n.userObject = File(it, f.hidden) }
        }
        shown = collectFiles(root)
        repaint()
    }

    /** a → b → c with single children becomes "a/b/c". */
    private fun compact(node: DefaultMutableTreeNode) {
        for (i in 0 until node.childCount) {
            var child = node.getChildAt(i) as DefaultMutableTreeNode
            while (child.userObject is Dir && child.childCount == 1 &&
                (child.getChildAt(0) as DefaultMutableTreeNode).userObject is Dir
            ) {
                val only = child.getChildAt(0) as DefaultMutableTreeNode
                val merged = DefaultMutableTreeNode(Dir((child.userObject as Dir).name + "/" + (only.userObject as Dir).name))
                while (only.childCount > 0) merged.add(only.getChildAt(0) as DefaultMutableTreeNode)
                node.remove(i)
                node.insert(merged, i)
                child = merged
            }
            compact(child)
        }
        sortChildren(node)
    }

    private fun sortChildren(node: DefaultMutableTreeNode) {
        val children = (0 until node.childCount).map { node.getChildAt(it) as DefaultMutableTreeNode }
        val sorted = children.sortedWith(compareBy({ it.userObject !is Dir }, { label(it.userObject).lowercase() }))
        node.removeAllChildren()
        sorted.forEach { node.add(it) }
    }

    private fun label(o: Any?): String = when (o) {
        is Dir -> o.name
        is File -> o.change.displayPath.substringAfterLast('/')
        else -> ""
    }

    private fun collectFiles(node: DefaultMutableTreeNode): List<FileChange> {
        val out = ArrayList<FileChange>()
        val e = node.preorderEnumeration()
        while (e.hasMoreElements()) ((e.nextElement() as DefaultMutableTreeNode).userObject as? File)?.let { out += it.change }
        return out
    }

    private fun openSelected() {
        val node = lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        val file = node.userObject as? File ?: return
        onOpen(file.change, shown)
    }

    private inner class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
        ) {
            when (val o = (value as? DefaultMutableTreeNode)?.userObject) {
                is Dir -> {
                    icon = AllIcons.Nodes.Folder
                    append(o.name)
                }
                is File -> {
                    val c = o.change
                    val name = c.displayPath.substringAfterLast('/')
                    val viewed = isViewed(c)
                    icon = FileTypeManager.getInstance().getFileTypeByFileName(name).icon
                    val color = when {
                        o.hidden || viewed -> JBColor.GRAY
                        c.newFile -> ADDED
                        c.deletedFile -> DELETED
                        c.renamedFile -> RENAMED
                        else -> null
                    }
                    var style = if (c.deletedFile) SimpleTextAttributes.STYLE_STRIKEOUT else SimpleTextAttributes.STYLE_PLAIN
                    if (o.hidden) style = style or SimpleTextAttributes.STYLE_ITALIC
                    append(name, SimpleTextAttributes(style, color))
                    if (c.renamedFile) append("  ← ${c.oldPath.substringAfterLast('/')}", SimpleTextAttributes.GRAYED_ATTRIBUTES)

                    val (added, removed) = c.stats
                    if (added > 0) append("  +$added", SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, PLUS))
                    if (removed > 0) append(" −$removed", SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, MINUS))
                    if (viewed) append("  " + msg("tree.viewed"), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    if (o.hidden) append("  " + msg("tree.hidden"), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    if (c.tooLarge) append("  " + msg("tree.tooLarge"), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
            }
        }
    }

    companion object {
        private val ADDED = JBColor(0x2E7D32, 0x6AAB73)
        private val DELETED = JBColor(0x757575, 0x8C8C8C)
        private val RENAMED = JBColor(0x1565C0, 0x6897BB)
        private val PLUS = JBColor(0x2E7D32, 0x6AAB73)
        private val MINUS = JBColor(0xC62828, 0xE06C75)
    }
}
