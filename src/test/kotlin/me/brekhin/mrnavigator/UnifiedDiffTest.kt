package me.brekhin.mrnavigator

import me.brekhin.mrnavigator.api.FileChange
import me.brekhin.mrnavigator.core.UnifiedDiff
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnifiedDiffTest {
    @Test
    fun fillsTooLargeFileFromLocalDiff() {
        val big = FileChange("old/a.go", "src/a.go", false, false, true, "", tooLarge = true)
        // A rename split into delete + add (low similarity): the entry of the new path is taken.
        val local = """
            diff --git a/old/a.go b/old/a.go
            deleted file mode 100644
            --- a/old/a.go
            +++ /dev/null
            @@ -1 +0,0 @@
            -gone
            diff --git a/src/a.go b/src/a.go
            --- a/src/a.go
            +++ b/src/a.go
            @@ -1,2 +1,2 @@
             x
            -y
            +z
        """.trimIndent()
        val filled = UnifiedDiff.fill(big, local)
        assertFalse(filled.tooLarge)
        assertEquals("@@ -1,2 +1,2 @@\n x\n-y\n+z", filled.diff)
        assertEquals(1 to 1, filled.stats)
        assertEquals("old/a.go", filled.oldPath)
        // A binary file has no hunks: it stays as the server gave it.
        assertEquals(big, UnifiedDiff.fill(big, "diff --git a/src/a.go b/src/a.go\nBinary files a/src/a.go and b/src/a.go differ"))
        assertEquals(big, UnifiedDiff.fill(big, ""))
    }

    @Test
    fun splitsGitDiff() {
        val diff = """
            diff --git a/src/a.go b/src/a.go
            index 1111111..2222222 100644
            --- a/src/a.go
            +++ b/src/a.go
            @@ -1,2 +1,2 @@
             x
            -y
            +z
            diff --git a/new.go b/new.go
            new file mode 100644
            --- /dev/null
            +++ b/new.go
            @@ -0,0 +1 @@
            +n
            diff --git a/gone.go b/gone.go
            deleted file mode 100644
            --- a/gone.go
            +++ /dev/null
            @@ -1 +0,0 @@
            -g
            diff --git a/old name.go b/new name.go
            similarity index 100%
            rename from old name.go
            rename to new name.go
            diff --git a/logo.png b/logo.png
            Binary files a/logo.png and b/logo.png differ
        """.trimIndent() + "\n"
        val files = UnifiedDiff.split(diff)
        assertEquals(listOf("src/a.go", "new.go", "gone.go", "new name.go", "logo.png"), files.map { it.displayPath })

        val a = files[0]
        assertEquals("@@ -1,2 +1,2 @@\n x\n-y\n+z", a.diff)
        assertEquals(1 to 1, a.stats)
        assertTrue(files[1].newFile); assertEquals("new.go", files[1].oldPath)
        assertTrue(files[2].deletedFile); assertEquals("gone.go", files[2].newPath)
        val renamed = files[3]
        assertTrue(renamed.renamedFile); assertEquals("old name.go", renamed.oldPath); assertEquals("", renamed.diff)
        assertFalse(files[4].newFile); assertEquals("logo.png", files[4].oldPath); assertEquals("", files[4].diff)
    }

    @Test
    fun bitbucketServerPrefixes() {
        val files = UnifiedDiff.split("diff --git src://a.go dst://a.go\n--- src://a.go\n+++ dst://a.go\n@@ -1 +1 @@\n-a\n+b\n")
        assertEquals(1, files.size)
        assertEquals("a.go", files[0].oldPath); assertEquals("a.go", files[0].newPath)
        assertEquals("@@ -1 +1 @@\n-a\n+b", files[0].diff)
    }

    @Test
    fun quotedPaths() {
        // git quotes non-ASCII names as octal bytes of UTF-8: "При.go".
        val p = "\"a/\\320\\237\\321\\200\\320\\270.go\""
        val diff = "diff --git $p ${p.replace("a/", "b/")}\n--- $p\n+++ ${p.replace("a/", "b/")}\n@@ -1 +1 @@\n-a\n+b\n"
        assertEquals("При.go", UnifiedDiff.split(diff).single().newPath)
        val q = "\"a/say \\\"hi\\\"\\t.go\""
        val quoted = "diff --git $q ${q.replace("a/", "b/")}\n--- $q\n+++ ${q.replace("a/", "b/")}\n@@ -1 +1 @@\n-a\n+b\n"
        assertEquals("say \"hi\"\t.go", UnifiedDiff.split(quoted).single().newPath)
    }

    @Test
    fun emptyDiff() {
        assertEquals(emptyList(), UnifiedDiff.split(""))
    }
}
