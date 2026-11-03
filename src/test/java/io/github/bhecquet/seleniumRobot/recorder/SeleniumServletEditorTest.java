package io.github.bhecquet.seleniumRobot.recorder;

import com.intellij.openapi.editor.Editor;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.lang.reflect.Method;

public class SeleniumServletEditorTest extends BasePlatformTestCase {

    public void testShouldCreatePageObjectFileAndUpdateMain() throws Exception {
        myFixture.configureByText(
                "Main.java",
                "package covea.pic.jenkins.tests.selenium;\n\n"
                        + "public class Main {\n"
                        + "    public static void main(String[] args) {\n"
                        + "        <caret>\n"
                        + "    }\n"
                        + "}\n"
        );

        Editor sourceEditor = myFixture.getEditor();
        SeleniumServlet servlet = new SeleniumServlet(getProject());

        SeleniumAction action = new SeleniumAction();
        action.setCommand("pageLoad");
        action.setReason("hashchange");
        action.setUrl("http://localhost/page.html#locators");
        action.setTitle("Locators");

        Method method = SeleniumServlet.class.getDeclaredMethod(
                "insertPageObject",
                Editor.class,
                SeleniumAction.class
        );
        method.setAccessible(true);

        Editor pageEditor = (Editor) method.invoke(
                servlet,
                sourceEditor,
                action
        );

        String mainContent = sourceEditor.getDocument().getText();

        assertTrue(mainContent.contains(
                "LocatorsPage locatorsPage = new LocatorsPage();"
        ));
        assertTrue(mainContent.contains("locatorsPage.execute();"));

        assertNotNull(pageEditor);

        String pageContent = pageEditor.getDocument().getText();
        assertTrue(pageContent.contains("public class LocatorsPage"));
        assertTrue(pageContent.contains("public LocatorsPage()"));
        assertTrue(pageContent.contains("public void execute()"));

        int caretOffset = pageEditor.getCaretModel().getOffset();
        int methodStart = pageContent.indexOf("public void execute()");
        int methodEnd = pageContent.indexOf("}", methodStart);

        assertTrue(caretOffset > methodStart);
        assertTrue(caretOffset < methodEnd);
    }

    private SeleniumAction createPageAction(
            String url,
            String title
    ) {
        SeleniumAction action =
                new SeleniumAction();

        action.setCommand("pageLoad");
        action.setReason("hashchange");
        action.setUrl(url);
        action.setTitle(title);

        return action;
    }

    private Editor invokeInsertPageObject(
            SeleniumServlet servlet,
            Editor sourceEditor,
            SeleniumAction action
    ) throws Exception {
        Method method =
                SeleniumServlet.class.getDeclaredMethod(
                        "insertPageObject",
                        Editor.class,
                        SeleniumAction.class
                );

        method.setAccessible(true);

        return (Editor) method.invoke(
                servlet,
                sourceEditor,
                action
        );
    }

    private int countOccurrences(
            String source,
            String searchedText
    ) {
        int count = 0;
        int position = 0;

        while ((position = source.indexOf(
                searchedText,
                position
        )) >= 0) {
            count++;
            position += searchedText.length();
        }

        return count;
    }

    public void testShouldDeclarePageInstanceOnlyOnce()
            throws Exception {
        myFixture.configureByText(
                "Main.java",
                "package covea.pic.jenkins.tests.selenium;\n\n"
                        + "public class Main {\n"
                        + "    public static void main(String[] args) {\n"
                        + "        <caret>\n"
                        + "    }\n"
                        + "}\n"
        );

        Editor mainEditor =
                myFixture.getEditor();

        SeleniumServlet servlet =
                new SeleniumServlet(getProject());

        SeleniumAction firstVisit =
                createPageAction(
                        "http://localhost/page.html#locators",
                        "Locators"
                );

        SeleniumAction secondVisit =
                createPageAction(
                        "http://localhost/page.html#locators",
                        "Locators"
                );

        invokeInsertPageObject(
                servlet,
                mainEditor,
                firstVisit
        );

        invokeInsertPageObject(
                servlet,
                mainEditor,
                secondVisit
        );

        String mainContent =
                mainEditor.getDocument().getText();

        String declaration =
                "LocatorsPage locatorsPage "
                        + "= new LocatorsPage();";

        assertEquals(
                1,
                countOccurrences(
                        mainContent,
                        declaration
                )
        );

        assertTrue(
                mainContent.contains(
                        "locatorsPage.execute();"
                )
        );

        assertTrue(
                mainContent.contains(
                        "locatorsPage.execute2();"
                )
        );
    }

    public void testShouldCreateExecute2OnSecondVisit()
            throws Exception {
        myFixture.configureByText(
                "Main.java",
                "package covea.pic.jenkins.tests.selenium;\n\n"
                        + "public class Main {\n"
                        + "    public static void main(String[] args) {\n"
                        + "        <caret>\n"
                        + "    }\n"
                        + "}\n"
        );

        Editor mainEditor =
                myFixture.getEditor();

        SeleniumServlet servlet =
                new SeleniumServlet(getProject());

        SeleniumAction action =
                createPageAction(
                        "http://localhost/page.html#locators",
                        "Locators"
                );

        invokeInsertPageObject(
                servlet,
                mainEditor,
                action
        );

        Editor pageEditor =
                invokeInsertPageObject(
                        servlet,
                        mainEditor,
                        action
                );

        assertNotNull(pageEditor);

        String pageContent =
                pageEditor.getDocument().getText();

        assertTrue(
                pageContent.contains(
                        "public void execute()"
                )
        );

        assertTrue(
                pageContent.contains(
                        "public void execute2()"
                )
        );

        String mainContent =
                mainEditor.getDocument().getText();

        assertTrue(
                mainContent.contains(
                        "locatorsPage.execute();"
                )
        );

        assertTrue(
                mainContent.contains(
                        "locatorsPage.execute2();"
                )
        );
    }

    public void testShouldPreservePageVisitOrderInMain()
            throws Exception {
        myFixture.configureByText(
                "Main.java",
                "package covea.pic.jenkins.tests.selenium;\n\n"
                        + "public class Main {\n"
                        + "    public static void main(String[] args) {\n"
                        + "        <caret>\n"
                        + "    }\n"
                        + "}\n"
        );

        Editor mainEditor =
                myFixture.getEditor();

        SeleniumServlet servlet =
                new SeleniumServlet(getProject());

        SeleniumAction accueilAction =
                createPageAction(
                        "http://localhost/#accueil",
                        "Accueil"
                );

        SeleniumAction connexionAction =
                createPageAction(
                        "http://localhost/#connexion",
                        "Connexion"
                );

        invokeInsertPageObject(
                servlet,
                mainEditor,
                accueilAction
        );

        invokeInsertPageObject(
                servlet,
                mainEditor,
                connexionAction
        );

        invokeInsertPageObject(
                servlet,
                mainEditor,
                accueilAction
        );

        String mainContent =
                mainEditor.getDocument().getText();

        int accueilFirstVisit =
                mainContent.indexOf(
                        "accueilPage.execute();"
                );

        int connexionFirstVisit =
                mainContent.indexOf(
                        "connexionPage.execute();"
                );

        int accueilSecondVisit =
                mainContent.indexOf(
                        "accueilPage.execute2();"
                );

        assertTrue(accueilFirstVisit >= 0);
        assertTrue(connexionFirstVisit >= 0);
        assertTrue(accueilSecondVisit >= 0);

        assertTrue(
                accueilFirstVisit
                        < connexionFirstVisit
        );

        assertTrue(
                connexionFirstVisit
                        < accueilSecondVisit
        );

        assertEquals(
                1,
                countOccurrences(
                        mainContent,
                        "AccueilPage accueilPage "
                                + "= new AccueilPage();"
                )
        );

        assertEquals(1, countOccurrences(mainContent, "ConnexionPage connexionPage " + "= new ConnexionPage();"
                )
        );
    }
}
