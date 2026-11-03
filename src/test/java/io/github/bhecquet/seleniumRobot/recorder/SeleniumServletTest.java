package io.github.bhecquet.seleniumRobot.recorder;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;

public class SeleniumServletTest {

    private SeleniumServlet servlet;

    @Before
    public void setUp() {
        servlet = new SeleniumServlet(null);
    }

    @Test
    public void shouldBuildMethodNameFromUrlFragment() throws Exception {
        assertEquals(
                "locatorsPage",
                invokeStringMethod(
                        "buildPageMethodName",
                        new Class<?>[]{String.class, String.class},
                        "http://localhost/page.html#locators",
                        "Page de test"
                )
        );
    }

    @Test
    public void shouldBuildMethodNameFromLastPathSegment() throws Exception {
        assertEquals(
                "detailsPage",
                invokeStringMethod(
                        "buildPageMethodName",
                        new Class<?>[]{String.class, String.class},
                        "https://application.example/customer/details",
                        "Customer details"
                )
        );
    }

    @Test
    public void shouldRemoveHtmlExtensionFromMethodName() throws Exception {
        assertEquals(
                "customerDetailsPage",
                invokeStringMethod(
                        "buildPageMethodName",
                        new Class<?>[]{String.class, String.class},
                        "https://application.example/customer-details.html",
                        "Customer details"
                )
        );
    }

    @Test
    public void shouldUseTitleWhenUrlHasNoFragmentOrPath() throws Exception {
        assertEquals(
                "customerDetailsPage",
                invokeStringMethod(
                        "buildPageMethodName",
                        new Class<?>[]{String.class, String.class},
                        "https://application.example/",
                        "Customer details"
                )
        );
    }

    @Test
    public void shouldUseGenericNameWhenUrlAndTitleAreEmpty() throws Exception {
        assertEquals(
                "newPage",
                invokeStringMethod(
                        "buildPageMethodName",
                        new Class<?>[]{String.class, String.class},
                        "",
                        ""
                )
        );
    }

    @Test
    public void shouldPrefixMethodNameStartingWithNumber() throws Exception {
        assertEquals(
                "page123ResultsPage",
                invokeStringMethod(
                        "buildPageMethodName",
                        new Class<?>[]{String.class, String.class},
                        "https://application.example/#123-results",
                        "Results"
                )
        );
    }

    private String invokeStringMethod(
            String name,
            Class<?>[] parameterTypes,
            Object... arguments
    ) throws Exception {
        Method method = SeleniumServlet.class.getDeclaredMethod(
                name,
                parameterTypes
        );
        method.setAccessible(true);
        return (String) method.invoke(servlet, arguments);
    }
}
