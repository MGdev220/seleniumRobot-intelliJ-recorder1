package io.github.bhecquet.seleniumRobot.recorder;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;


public class PageClassManagerEditorTest extends BasePlatformTestCase {

    public void testShouldCreatePageFileInSameDirectory() {
        VirtualFile mainFile = myFixture.addFileToProject(
                "src/test/java/covea/pic/jenkins/tests/selenium/Main.java",
                "package covea.pic.jenkins.tests.selenium;\n\n"
                        + "public class Main {\n"
                        + "    public static void main(String[] args) {\n"
                        + "    }\n"
                        + "}\n"
        ).getVirtualFile();

        PageClassManager manager = new PageClassManager(getProject());

        VirtualFile pageFile = manager.createOrFindPageFile(
                mainFile.getParent(),
                "covea.pic.jenkins.tests.selenium",
                "locatorsPage"
        );

        assertNotNull(pageFile);
        assertEquals("LocatorsPage.java", pageFile.getName());
        assertEquals(mainFile.getParent(), pageFile.getParent());

        String content = readFile(pageFile);

        assertTrue(content.contains("package covea.pic.jenkins.tests.selenium;"));
        assertTrue(content.contains("public class LocatorsPage"));
        assertTrue(content.contains("public void execute()"));
    }

    public void testShouldReuseExistingPageFileWithoutOverwritingIt() {
        VirtualFile mainFile = myFixture.addFileToProject(
                "src/test/java/covea/pic/jenkins/tests/selenium/Main.java",
                "package covea.pic.jenkins.tests.selenium;\n\n"
                        + "public class Main {\n"
                        + "}\n"
        ).getVirtualFile();

        VirtualFile existingPageFile = myFixture.addFileToProject(
                "src/test/java/covea/pic/jenkins/tests/selenium/LocatorsPage.java",
                "package covea.pic.jenkins.tests.selenium;\n\n"
                        + "public class LocatorsPage {\n"
                        + "    public void execute() {\n"
                        + "        existingAction();\n"
                        + "    }\n"
                        + "}\n"
        ).getVirtualFile();

        PageClassManager manager = new PageClassManager(getProject());

        VirtualFile result = manager.createOrFindPageFile(
                mainFile.getParent(),
                "covea.pic.jenkins.tests.selenium",
                "locatorsPage"
        );

        assertEquals(existingPageFile, result);
        assertTrue(readFile(result).contains("existingAction();"));
    }

    public void testShouldOpenPageFileAndMoveCaretInsideExecute() {
        VirtualFile pageFile = myFixture.addFileToProject(
                "src/test/java/covea/pic/jenkins/tests/selenium/LocatorsPage.java",
                "package covea.pic.jenkins.tests.selenium;\n\n"
                        + "public class LocatorsPage {\n\n"
                        + "    public void execute() {\n"
                        + "\n"
                        + "    }\n"
                        + "}\n"
        ).getVirtualFile();

        PageClassManager manager = new PageClassManager(getProject());
        Editor editor = manager.openPageEditor(pageFile);
        String content = editor.getDocument().getText();
        int caretOffset = editor.getCaretModel().getOffset();
        int methodStart = content.indexOf("public void execute()");
        int methodEnd = content.indexOf("}", methodStart);

        assertNotNull(editor);
        assertEquals(pageFile, FileEditorManager.getInstance(getProject()).getSelectedTextEditor().getVirtualFile());
        assertTrue(caretOffset > methodStart);
        assertTrue(caretOffset < methodEnd);
    }

    public void testShouldCreatePageFileWithoutPackage() {
        VirtualFile mainFile = myFixture.addFileToProject(
                "Main.java",
                "public class Main {\n"
                        + "}\n"
        ).getVirtualFile();

        PageClassManager manager = new PageClassManager(getProject());

        VirtualFile pageFile = manager.createOrFindPageFile(
                mainFile.getParent(),
                "",
                "textsPage"
        );

        String content = readFile(pageFile);

        assertEquals("TextsPage.java", pageFile.getName());
        assertTrue(content.startsWith("public class TextsPage"));
        assertFalse(content.contains("package "));
    }

    public void testShouldFindExecuteBodyOffset() {
        String content = "public class LocatorsPage {\n"
                + "    public void execute() {\n"
                + "        existingAction();\n"
                + "    }\n"
                + "}\n";

        PageClassManager manager = new PageClassManager(getProject());
        int offset = manager.findExecuteBodyOffset(content);

        assertEquals(content.indexOf("        existingAction();"), offset);
    }

    public void testShouldRejectPageWithoutExecuteMethod() {
        PageClassManager manager = new PageClassManager(getProject());

        try {
            manager.findExecuteBodyOffset("public class LocatorsPage {}");
            fail("An exception should be declared");
        } catch (IllegalStateException error) {
            assertTrue(error.getMessage().contains("execute"));
        }
    }

    private String readFile(VirtualFile file) {
        try {
            return VfsUtilCore.loadText(file);
        } catch (Exception error) {
            throw new AssertionError("Impossible de lire " + file.getName(), error);
        }
    }
}