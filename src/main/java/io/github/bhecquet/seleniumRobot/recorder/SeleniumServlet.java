package io.github.bhecquet.seleniumRobot.recorder;

import com.google.gson.Gson;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;


public class SeleniumServlet extends HttpServlet {

    private Project project;

    private final PageClassManager pageClassManager;
    private volatile Editor mainEditor;
    private volatile Editor currentTargetEditor;
    private volatile boolean initialPageLoadHandled;
    private volatile String lastPageUrl;
    private volatile String activePageUrl;
    private volatile String pendingPageUrl;
    private volatile String pendingPageTitle;
    private volatile boolean actionRecordedSinceLastPageLoad;
    private final ConcurrentMap<String, Integer> pageVisitCounts = new ConcurrentHashMap<>();


    private static final Logger LOG = Logger.getInstance(SeleniumServlet.class);

    private static final java.util.concurrent.ConcurrentMap<String, String> SELECTOR_TO_VAR = new java.util.concurrent.ConcurrentHashMap<>();


    private String keyFor(String selectorLiteral, String frameVarOrNull) {
        return (frameVarOrNull == null ? "_" : frameVarOrNull) + "|" + selectorLiteral.trim();
    }

    private String getText(Editor editor) {
        return editor.getDocument().getText();
    }


    public SeleniumServlet(Project project) {
        this.project = project;
        this.pageClassManager = new PageClassManager(project);
        resetRecordingSession();
    }

    public void resetRecordingSession() {
        mainEditor = null;
        currentTargetEditor = null;
        lastPageUrl = null;
        activePageUrl = null;
        pendingPageUrl = null;
        pendingPageTitle = null;
        initialPageLoadHandled = false;
        actionRecordedSinceLastPageLoad = false;
        SELECTOR_TO_VAR.clear();

        pageVisitCounts.clear();

        LOG.info("[Recorder] Recording session reset");
    }

    @Override
    protected void doPost(
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        Editor selectedEditor = FileEditorManager.getInstance(project).getSelectedTextEditor();

        if (selectedEditor != null) {
            if (mainEditor == null) {
                mainEditor = selectedEditor;
                currentTargetEditor = selectedEditor;
                LOG.info("[Recorder] Main editor initialized");
            }
            SeleniumAction action = new Gson().fromJson(request.getReader(), SeleniumAction.class);

            LOG.info("[Recorder] POST /event reçu");
            LOG.info("[Recorder] COMMAND = " + action.getCommand());

            if ("pageLoad".equals(action.getCommand())) {
                String pageUrl = normalizePageUrl(action.getUrl());
                LOG.info("[Recorder] PAGE LOAD DETECTED" + " reason=" + action.getReason() + " url=" + pageUrl + " title=" + action.getTitle());
                if (isRecordablePageUrl(pageUrl)
                        && !pageUrl.equals(lastPageUrl)) {
                    pendingPageUrl = pageUrl;
                    pendingPageTitle = action.getTitle();
                    lastPageUrl = pageUrl;

                    LOG.info("[Recorder] Page pending until first action" + " url=" + pendingPageUrl);
                } else {
                    LOG.info("[Recorder] pageLoad ignored" + " url=" + pageUrl);
                }
            } else {
                String actionUrl = normalizePageUrl(action.getUrl());

                if (!isRecordablePageUrl(actionUrl)) {
                    LOG.info("[Recorder] Action ignored for URL: " + actionUrl);
                } else {
                    boolean pageMustChange = currentTargetEditor == null || activePageUrl == null || !actionUrl.equals(activePageUrl);

                    if (pageMustChange) {
                        Editor sourceEditor = currentTargetEditor != null ? currentTargetEditor : mainEditor;
                        if (sourceEditor == null) {
                            sourceEditor = selectedEditor;
                        }

                        currentTargetEditor = insertPageObject(sourceEditor, action);
                        activePageUrl = actionUrl;
                        pendingPageUrl = null;
                        pendingPageTitle = null;

                        LOG.info("[Recorder] Active Page Object changed" + " url=" + activePageUrl + " targetEditor=" + getEditorFilePath(currentTargetEditor));
                    }

                    Editor targetEditor = currentTargetEditor != null ? currentTargetEditor : mainEditor;

                    if (targetEditor == null) {
                        targetEditor = selectedEditor;
                    }

                    currentTargetEditor = targetEditor;
                    actionRecordedSinceLastPageLoad = true;

                    if (action.getFramePath() != null) {
                        LOG.info("[Recorder] framePath size="
                                + action.getFramePath().size());
                    }

                    insertImports(targetEditor, action);
                    insertElement(targetEditor, action);
                    insertElementAction(targetEditor, action);
                }
            }
        } else {
            LOG.warn("[Recorder] No Java editor selected");
        }

        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type");
        response.setStatus(HttpServletResponse.SC_OK);
        response.getOutputStream().print("OK");
        response.getOutputStream().flush();
    }

    private boolean isRecordablePageUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }

        String normalizedUrl = url.toLowerCase();

        return !normalizedUrl.startsWith("chrome://")
                && !normalizedUrl.startsWith("chrome-extension://")
                && !normalizedUrl.startsWith("edge://")
                && !normalizedUrl.equals("about:blank")
                && !normalizedUrl.startsWith("devtools://");
    }

    private String normalizePageUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }

        try {
            URI uri = URI.create(url);

            return new URI(
                    uri.getScheme(),
                    uri.getAuthority(),
                    uri.getPath(),
                    null,
                    uri.getFragment()
            ).toString();
        } catch (Exception error) {
            LOG.warn("[Recorder] Impossible de normaliser l'URL : " + url);
            return url;
        }
    }

    private String getEditorFilePath(Editor editor) {
        if (editor == null) {
            return "null";
        }

        VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        return file == null ? "no-virtual-file" : file.getPath();
    }

    private int findFirstMethodStart(String text, int searchStart) {
        java.util.regex.Pattern methodPattern =
                java.util.regex.Pattern.compile("(?m)^\\s*" + "(public|protected|private)\\s+" + "[\\w<>,\\s\\[\\]]+\\s+" + "\\w+\\s*\\(");
        java.util.regex.Matcher matcher = methodPattern.matcher(text);

        return matcher.find(searchStart) ? matcher.start() : -1;
    }


    private Editor insertPageObject(
            Editor sourceEditor,
            SeleniumAction action
    ) {
        LOG.info("[Recorder] INSERT_PAGE_OBJECT CALLED");

        String url = action.getUrl();
        String pageKey = normalizePageUrl(url);

        if (url == null || url.isBlank()) {
            LOG.warn("[Recorder] URL  page abscent");
            return sourceEditor;
        }

        Editor directoryEditor = mainEditor != null
                ? mainEditor
                : sourceEditor;

        VirtualFile sourceFile = FileDocumentManager.getInstance().getFile(directoryEditor.getDocument());

        if (sourceFile == null || sourceFile.getParent() == null) {
            return sourceEditor;
        }

        VirtualFile sourceDirectory = sourceFile.getParent();
        String pageMethodName = buildPageMethodName(url, action.getTitle());
        String packageName = extractPackageName(directoryEditor.getDocument().getText());

        int visitNumber = pageVisitCounts.merge(pageKey, 1, Integer::sum);

        LOG.info("[Recorder] Page visit" + " url=" + url + " visitNumber=" + visitNumber);

        VirtualFile pageFile = pageClassManager.createOrFindPageFile(sourceDirectory, packageName, pageMethodName);

        if (pageFile == null) {
            throw new IllegalStateException(" Page Object not created for " + pageMethodName);
        }

        pageFile.refresh(false, false);
        sourceDirectory.refresh(false, true);

        Editor pageEditor = pageClassManager.openPageEditor(
                pageFile,
                visitNumber
        );

        if (pageEditor == null) {
            throw new IllegalStateException("Impossible to open " + pageFile.getPath());
        }

        String methodCall = pageClassManager.buildMethodCall(
                pageMethodName,
                visitNumber
        );

        Editor scenarioEditor = mainEditor != null ? mainEditor : sourceEditor;

        insertPageObjectCall(scenarioEditor, methodCall);

        return pageEditor;
    }

    private void insertPageObjectCall(
            Editor editor,
            String methodCall
    ) {
        WriteCommandAction.runWriteCommandAction(project, () -> {
                    var document = editor.getDocument();
                    if (document.getText().contains(methodCall)) {
                        LOG.info("[Recorder] Page call already present: " + methodCall);
                        return;
                    }
                    int caretOffset = editor.getCaretModel().getOffset();
                    String code = "\t\t" + methodCall + "\n";
                    document.insertString(caretOffset, code);
                    editor.getCaretModel().moveToOffset(caretOffset + code.length()
                    );
                }
        );
    }

    private String extractPackageName(String javaCode) {
        if (javaCode == null || javaCode.isBlank()) {
            return "";
        }

        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?m)^\\s*package\\s+([^;]+);")
                .matcher(javaCode);

        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private String buildPageMethodName(String url, String title) {
        String source = "";
        try {
            java.net.URI uri = java.net.URI.create(url);
            String fragment = uri.getFragment();
            if (fragment != null && !fragment.isBlank()) {
                source = fragment;
            } else {
                String path = uri.getPath();
                if (path != null && !path.isBlank() && !"/".equals(path)) {
                    String[] pathParts = path.split("/");
                    for (int index = pathParts.length - 1; index >= 0; index--) {
                        if (pathParts[index] != null && !pathParts[index].isBlank()
                        ) {
                            source = pathParts[index];
                            break;
                        }
                    }
                }
            }
        } catch (
                IllegalArgumentException error
        ) {
            LOG.warn("[Recorder] Invalid page URL: " + url);
        }

        if (
                source == null || source.isBlank()) {
            source = title;
        }

        if (source == null || source.isBlank()) {
            source = "newPage";
        }

        source = source.replaceFirst("\\.[A-Za-z0-9]+$", "");

        String[] words = source.split("[^A-Za-z0-9]+");

        StringBuilder methodName = new StringBuilder();

        for (String word : words) {
            if (word == null || word.isBlank()) {
                continue;
            }

            if (methodName.isEmpty()) {
                methodName.append(word.substring(0, 1).toLowerCase());

                if (word.length() > 1) {
                    methodName.append(word.substring(1));
                }
            } else {
                methodName.append(word.substring(0, 1).toUpperCase());

                if (word.length() > 1) {
                    methodName.append(word.substring(1));
                }
            }
        }

        if (methodName.isEmpty()) {
            methodName.append("newPage");
        }

        if (Character.isDigit(methodName.charAt(0))) {
            methodName.insert(0, "page");
        }

        if (!methodName.toString().endsWith("Page")
        ) {
            methodName.append("Page");
        }

        return methodName.toString();
    }


    private boolean containsMethod(
            String text,
            String methodName
    ) {
        String pattern = "(?m)^\\s*" + "(public|protected|private)" + "\\s+static\\s+void\\s+" + java.util.regex.Pattern.quote(methodName)
                + "\\s*\\(";

        return java.util.regex.Pattern
                .compile(pattern)
                .matcher(text)
                .find();
    }

    private String insertElement(Editor editor, SeleniumAction seleniumAction) {

        WriteCommandAction.runWriteCommandAction(project, () -> {

            var doc = editor.getDocument();
            String text = doc.getText();

            int classOpen = text.indexOf('{');
            if (classOpen < 0) {
                return;
            }
            int classBodyStart = indexAfterLineEnd(text, classOpen);

            int framesBlockEnd = findAfterLastFrameDeclLineEnd(text, classBodyStart);


            if (
                    seleniumAction.getFramePath() != null
                            && !seleniumAction.getFramePath().isEmpty()
            ) {
                List<FrameInfo> framePath =
                        seleniumAction.getFramePath();

                /*
                 * Declaration of each iframe in order :
                 *
                 * document principal
                 * -> iframeA
                 * -> iframeB
                 * -> iframeC
                 */
                for (
                        int frameIndex = 0;
                        frameIndex < framePath.size();
                        frameIndex++
                ) {
                    FrameInfo currentFrame = framePath.get(frameIndex);

                    String frameVarName = computeFrameVarName(currentFrame);

                    String selector = currentFrame.getSelector();

                    if (
                            selector == null || selector.trim().isEmpty() || "null".equals(selector.trim()
                            )
                    ) {
                        if (
                                currentFrame.getId() != null && !currentFrame.getId().isBlank()
                        ) {
                            selector = "By.id(\"" + currentFrame.getId().trim() + "\")";
                        } else {
                            selector = "By.cssSelector(\"iframe\")";
                        }
                    }

                    String logicalId = computeFrameLogicalId(currentFrame);
                    String parentFrameReference = "";

                    if (frameIndex > 0) {
                        FrameInfo parentFrame = framePath.get(frameIndex - 1);

                        parentFrameReference = ", " + computeFrameVarName(parentFrame);
                    }

                    String frameDecl =
                            "\tprivate static FrameElement "
                                    + frameVarName
                                    + " = new FrameElement(\""
                                    + logicalId
                                    + "\", "
                                    + selector
                                    + parentFrameReference
                                    + ");\n";

                    if (!containsExactFrameDecl(text, frameVarName)) {
                        int insertPos = framesBlockEnd != -1 ? framesBlockEnd : classBodyStart;
                        doc.insertString(insertPos, frameDecl);

                        text = doc.getText();

                        framesBlockEnd = findAfterLastFrameDeclLineEnd(text, classBodyStart);
                    }
                }
            }

            String elementCode = seleniumAction.getWebElementString();
            String elementType = seleniumAction.getElementType();
            String elementName = seleniumAction.getElementName();
            String selectorLiteral = seleniumAction.getSelector();

            String frameVar = null;
            if (seleniumAction.getFramePath() != null && !seleniumAction.getFramePath().isEmpty()) {
                FrameInfo fr = seleniumAction.getFramePath().get(seleniumAction.getFramePath().size() - 1);
                frameVar = computeFrameVarName(fr);
            }


            Object[] existing = findFieldDeclBySelector(text, selectorLiteral, frameVar);

            if (existing != null) {
                String existingSelector = (existing.length >= 5) ? (String) existing[4] : null;

                boolean existingStrong = isStrongSelector(existingSelector);
                boolean incomingStrong = isStrongSelector(selectorLiteral);

                if (existingStrong && incomingStrong) {

                    String oldName = (String) existing[3];
                    SELECTOR_TO_VAR.put(keyFor(selectorLiteral, frameVar), oldName);
                    return;
                }

            }
            if (existing != null) {
                int start = (int) existing[0];
                int end = (int) existing[1];
                String oldType = (String) existing[2];
                String oldName = (String) existing[3];


                boolean needUpgrade =
                        "HtmlElement".equals(oldType)
                                && ("TextFieldElement".equals(elementType)
                                || "PasswordFieldElement".equals(elementType)
                                || "TextAreaElement".equals(elementType));

                if (needUpgrade) {
                    String newText = replaceFieldType(text, start, end, oldType, elementType);
                    editor.getDocument().setText(newText);


                    SELECTOR_TO_VAR.put(keyFor(selectorLiteral, frameVar), oldName);


                    var d2 = editor.getDocument();
                    String upd2 = d2.getText();
                    upd2 = reformatFieldZone(upd2);
                    d2.setText(upd2);

                    return;
                }


                SELECTOR_TO_VAR.put(keyFor(selectorLiteral, frameVar), oldName);
                return;
            }
            String resolvedElementName = elementName;
            if (existing == null && containsElementName(text, resolvedElementName)
            ) {
                String collisionKey = selectorLiteral + "|" + (frameVar == null ? "" : frameVar);

                resolvedElementName = elementName + "_" + shortHash(collisionKey);

                elementCode = elementCode.replaceFirst("\\b" + java.util.regex.Pattern.quote(elementName)
                                + "\\b(?=\\s*=)",
                        java.util.regex.Matcher.quoteReplacement(resolvedElementName));
            }
            if (!containsExactElementDecl(text, elementType, resolvedElementName)) {
                text = doc.getText();
                int insertPos = findFirstMethodStart(text, classBodyStart);

                if (insertPos < 0) {
                    insertPos = text.lastIndexOf('}');
                }

                elementCode = elementCode.replaceFirst("^\\n+", "");
                doc.insertString(insertPos, elementCode);
                text = doc.getText();
            }
            SELECTOR_TO_VAR.put(keyFor(selectorLiteral, frameVar), resolvedElementName);


            var document = editor.getDocument();
            String updated = document.getText();
            updated = reformatFieldZone(updated);
            document.setText(updated);
        });

        return null;
    }

    /* ======== Helpers  ======== */


    private int findAfterLastFrameDeclLineEnd(String text, int searchStart) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(?m)^\\s*private\\s+static\\s+FrameElement\\s+\\w+\\s*=\\s*new\\s+FrameElement\\s*\\([^;]*\\);\\s*$"
        );
        java.util.regex.Matcher m = p.matcher(text);
        int afterLineEnd = -1;
        while (m.find(searchStart)) {
            int lineEnd = text.indexOf('\n', m.end());
            afterLineEnd = (lineEnd == -1) ? text.length() : lineEnd + 1;
            searchStart = m.end();
        }
        return afterLineEnd;
    }

    private boolean containsExactFrameDecl(String text, String frameVarName) {
        String pattern = "(?m)^\\s*private\\s+static\\s+FrameElement\\s+" + java.util.regex.Pattern.quote(frameVarName) + "\\s*=";
        return java.util.regex.Pattern.compile(pattern).matcher(text).find();
    }

    private boolean containsExactElementDecl(String text, String elementType, String elementName) {
        String pattern = "(?m)^\\s*private\\s+static\\s+" + java.util.regex.Pattern.quote(elementType) +
                "\\s+" + java.util.regex.Pattern.quote(elementName) + "\\s*=";
        return java.util.regex.Pattern.compile(pattern).matcher(text).find();
    }

    private boolean containsElementName(String text, String elementName) {
        if (text == null || elementName == null || elementName.isBlank()) {
            return false;
        }

        String pattern = "(?m)^\\s*private\\s+static\\s+" + "\\w+\\s+" + java.util.regex.Pattern.quote(elementName) + "\\s*=";

        return java.util.regex.Pattern
                .compile(pattern)
                .matcher(text)
                .find();
    }

    private String shortHash(String value) {
        int hash = value == null ? 0 : value.hashCode();
        String hexadecimal = Integer.toHexString(hash);

        return hexadecimal.length() > 4
                ? hexadecimal.substring(
                hexadecimal.length() - 4
        )
                : hexadecimal;
    }


    private boolean isStrongSelector(String selector) {
        if (selector == null) return false;
        return selector.startsWith("By.id(")
                || selector.startsWith("By.name(")
                || selector.startsWith("By.linkText(")
                || selector.contains("[data-testid=")
                || selector.contains("[aria-label=")
                || selector.contains("href=");
    }


    private void insertElementAction(Editor editor, SeleniumAction seleniumAction) {

        String cmd = seleniumAction.getCommand();
        String elementType = seleniumAction.getElementType();

        boolean isSelect = "SelectElement".equals(elementType) || "SelectList".equals(elementType);
        if (isSelect && ("click".equals(cmd) || "change".equals(cmd))) {
            return;
        }
        boolean isTypingCmd = "type".equals(cmd) || "sendKeys".equals(cmd) || "keyup".equals(cmd) || "keydown".equals(cmd);
        if (isTypingCmd && (seleniumAction.getValue() == null || seleniumAction.getValue().isEmpty())) {
            return;
        }

        WriteCommandAction.runWriteCommandAction(project, () -> {

            var doc = editor.getDocument();
            String text = doc.getText();
            int caretOffset = editor.getCaretModel().getCurrentCaret().getOffset();

            String selectorLiteral = seleniumAction.getSelector();
            String frameVar = null;
            if (seleniumAction.getFramePath() != null && !seleniumAction.getFramePath().isEmpty()) {
                FrameInfo fr = seleniumAction.getFramePath().get(seleniumAction.getFramePath().size() - 1);
                frameVar = computeFrameVarName(fr);
            }

            String initialVar = seleniumAction.getElementName(); // ex: usernameField_b309
            String code = seleniumAction.getFormattedCommand();


            String declaredName = SELECTOR_TO_VAR.get(keyFor(selectorLiteral, frameVar));

            if (declaredName == null) {
                Object[] ex = findFieldDeclBySelector(text, selectorLiteral, frameVar);
                if (ex != null) {
                    declaredName = (String) ex[3];
                    SELECTOR_TO_VAR.put(keyFor(selectorLiteral, frameVar), declaredName);
                }
            }


            String elementNameUsed = initialVar;
            if (declaredName != null && !declaredName.equals(initialVar)) {
                code = code.replace(initialVar + ".", declaredName + ".");
                elementNameUsed = declaredName;   // ← IMPORTANT
            }


            if ("click".equals(cmd)) {
                String prevLine = previousNonEmptyLine(text, caretOffset);
                if (prevLine != null && prevLine.contains(elementNameUsed + ".click(")) {
                    return;
                }
            }


            if (isTypingCmd) {
                int[] lastRange = findLastTypingCallRange(text, caretOffset, elementNameUsed);
                if (lastRange != null) {
                    doc.replaceString(lastRange[0], lastRange[1], code);
                    editor.getCaretModel().getCurrentCaret().moveToOffset(lastRange[0] + code.length());
                    return;
                }
            }


            if ("doubleClick".equals(cmd)) {

                String prevLine = previousNonEmptyLine(text, caretOffset);

                if (prevLine != null && prevLine.contains(elementNameUsed + ".click(")) {


                    int lineStart = text.lastIndexOf('\n', text.lastIndexOf(prevLine)) + 1;
                    int lineEnd = text.indexOf('\n', lineStart);
                    if (lineEnd < 0) lineEnd = text.length();

                    doc.deleteString(lineStart, lineEnd + 1);


                    editor.getCaretModel().getCurrentCaret().moveToOffset(lineStart);

                    caretOffset = lineStart;
                }
            }

            doc.insertString(caretOffset, code);
            editor.getCaretModel().getCurrentCaret().moveToOffset(caretOffset + code.length());
        });
    }

    private String previousNonEmptyLine(String text, int caretOffset) {
        int i = Math.min(caretOffset - 1, text.length() - 1);
        while (i >= 0 && (text.charAt(i) == '\n' || text.charAt(i) == '\r')) i--;
        if (i < 0) return null;

        int lineStart = text.lastIndexOf('\n', i) + 1;
        int lineEnd = text.indexOf('\n', lineStart);
        if (lineEnd < 0) lineEnd = text.length();

        String line = text.substring(lineStart, lineEnd).trim();
        return line.isEmpty() ? null : line;
    }

    private int[] findLastTypingCallRange(String text, int caretOffset, String elementName) {
        String p1 = elementName + ".sendKeys(";
        String p2 = elementName + ".setText(";

        int startSearch = Math.min(caretOffset, text.length());
        int i1 = text.lastIndexOf(p1, startSearch);
        int i2 = text.lastIndexOf(p2, startSearch);
        int idx = Math.max(i1, i2);
        if (idx < 0) return null;

        int lineStart = text.lastIndexOf('\n', idx) + 1;
        int lineEnd = text.indexOf('\n', idx);
        if (lineEnd < 0) lineEnd = text.length();


        String between = text.substring(lineEnd, Math.min(caretOffset, text.length()));
        if (!between.trim().isEmpty()) return null;

        int end = (lineEnd + 1 <= text.length()) ? lineEnd + 1 : lineEnd;
        return new int[]{lineStart, end};
    }

    @Override
    protected void doOptions(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type");
        response.setStatus(HttpServletResponse.SC_OK);
    }


    private void insertImports(Editor editor, SeleniumAction seleniumAction) {
        WriteCommandAction.runWriteCommandAction(project, () -> {
            var doc = editor.getDocument();
            String content = doc.getText();

            content = content
                    .replaceFirst("(?s)^\\s+(?=package\\s+[^;]+;)", "")
                    .replaceFirst("(?m)^(\\s*package\\s+[^;]+;)[ \\t]*\\R*", "$1\n");
            doc.setText(content);
            content = doc.getText();

            var pkgPat = java.util.regex.Pattern.compile("(?m)^\\s*package\\s+[^;]+;");
            var pkgM = pkgPat.matcher(content);
            int pkgEnd = pkgM.find() ? pkgM.end() : 0;

            var impLinePat = java.util.regex.Pattern.compile("(?m)^\\s*import\\s+([^;]+);\\s*$");
            var m = impLinePat.matcher(content);
            int firstImpStart = -1, lastImpEnd = -1;
            java.util.LinkedHashSet<String> existingFqns = new java.util.LinkedHashSet<>();
            while (m.find()) {
                if (m.start() >= pkgEnd) {
                    if (firstImpStart == -1) firstImpStart = m.start();
                    lastImpEnd = m.end();
                    existingFqns.add(m.group(1).trim());
                }
            }
            if (firstImpStart == -1) {
                firstImpStart = pkgEnd;
                lastImpEnd = pkgEnd;
            }

            java.util.Set<String> neededFqns = ImportBuilder.computeImports(
                    java.util.Collections.singletonList(seleniumAction)
            );

            java.util.Set<String> union = new java.util.TreeSet<>();
            union.addAll(existingFqns);
            union.addAll(neededFqns);

            StringBuilder canon = new StringBuilder();
            if (firstImpStart == pkgEnd) {
                canon.append("\n");
            }
            for (String fqn : union) {
                canon.append("import ").append(fqn).append(";\n");
            }

            doc.replaceString(firstImpStart, lastImpEnd, canon.toString());
        });
    }

    private int indexAfterLineEnd(String text, int pos) {
        int nl = text.indexOf('\n', pos);
        return nl >= 0 ? nl + 1 : pos + 1;
    }

    private String reformatFieldZone(String text) {
        int classOpen = text.indexOf('{');
        if (classOpen < 0) return text;

        int zoneStart = indexAfterLineEnd(text, classOpen);


        java.util.regex.Pattern methodStartPat = java.util.regex.Pattern.compile(
                "(?m)^\\s*(public|protected|private)\\s+[\\w<>,\\s\\[\\]]+\\s+\\w+\\s*\\("
        );
        java.util.regex.Matcher mm = methodStartPat.matcher(text);
        int zoneEnd = mm.find(zoneStart) ? mm.start() : text.lastIndexOf('}');
        if (zoneEnd <= zoneStart) zoneEnd = text.length();

        String before = text.substring(0, zoneStart);
        String zone = text.substring(zoneStart, zoneEnd);
        String after = text.substring(zoneEnd);


        zone = zone.replaceAll("(?m)\\n{3,}", "\n\n");

        java.util.regex.Pattern frameLine = java.util.regex.Pattern.compile(
                "(?m)^\\s*private\\s+static\\s+FrameElement\\s+\\w+\\s*=\\s*new\\s+FrameElement\\s*\\([^;]*\\);\\s*$"
        );
        java.util.regex.Pattern fieldLine = java.util.regex.Pattern.compile(
                "(?m)^\\s*private\\s+static\\s+\\w[\\w<>]*Element\\s+\\w+\\s*=\\s*new\\s+\\w[\\w<>]*Element\\s*\\([^;]*\\);\\s*$"
        );

        String[] lines = zone.split("\\R", -1);
        StringBuilder out = new StringBuilder();
        boolean seenAnyFrame = false;
        int lastFrameLineIndex = -1;


        for (int i = 0; i < lines.length; i++) {
            if (frameLine.matcher(lines[i]).find()) {
                seenAnyFrame = true;
                lastFrameLineIndex = i;
            }
        }

        boolean afterLastFrameBlankEmitted = !seenAnyFrame;
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String cur = raw;

            boolean isEmpty = cur.trim().isEmpty();


            if (seenAnyFrame && i > lastFrameLineIndex) {

                if (!afterLastFrameBlankEmitted) {

                    if (isEmpty) {

                        continue;
                    } else {

                        out.append("\n"); // <-- unique
                        afterLastFrameBlankEmitted = true;
                    }
                }

                if (isEmpty) {

                    continue;
                }
            }


            if (isEmpty) {

                String prev = lastNonEmptyLine(out);
                String next = nextNonEmpty(lines, i + 1);
                boolean prevIsField = prev != null && fieldLine.matcher(prev).find() && !frameLine.matcher(prev).find();
                boolean nextIsField = next != null && fieldLine.matcher(next).find() && !frameLine.matcher(next).find();

                if (prevIsField && nextIsField) {

                    continue;
                }
            }


            out.append(cur);
            if (i < lines.length - 1) out.append("\n");
        }


        String zoned = out.toString().replaceAll("(?m)\\n{3,}", "\n\n");
        return before + zoned + after;
    }

    private String lastNonEmptyLine(StringBuilder sb) {
        String s = sb.toString();
        int i = s.length() - 1;

        while (i >= 0 && (s.charAt(i) == '\n' || s.charAt(i) == '\r')) i--;
        if (i < 0) return null;
        int start = s.lastIndexOf("\n", i) + 1;
        String line = s.substring(start, i + 1);
        return line.trim().isEmpty() ? null : line;
    }

    private String nextNonEmpty(String[] lines, int from) {
        for (int i = from; i < lines.length; i++) {
            if (!lines[i].trim().isEmpty()) return lines[i];
        }
        return null;
    }

    private String computeFrameLogicalId(FrameInfo fr) {
        String id = fr.getId();
        if (id != null && !id.isBlank()) {
            return id.trim();
        }
        String sel = String.valueOf(fr.getSelector());
        return Integer.toHexString(sel.hashCode());
    }

    private String computeFrameVarName(FrameInfo fr) {
        String logicalId = computeFrameLogicalId(fr);
        String sanitized = logicalId.replaceAll("[^A-Za-z0-9_]", "_");
        return "frame_" + sanitized;
    }

    private Object[] findFieldDeclBySelector(String text, String selectorLiteral, String frameVarOrNull) {
        String selEsc = java.util.regex.Pattern.quote(selectorLiteral.trim());
        String framePart = (frameVarOrNull == null || frameVarOrNull.isBlank())
                ? "(?:\\s*,\\s*\\w+)?"
                : "\\s*,\\s*" + java.util.regex.Pattern.quote(frameVarOrNull);
        String rx = "(?m)^\\s*private\\s+static\\s+(\\w+)\\s+(\\w+)\\s*=\\s*new\\s+\\1\\s*\\([^;]*?"
                + selEsc + framePart + "\\s*\\)\\s*;\\s*$";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(rx);
        java.util.regex.Matcher m = p.matcher(text);
        if (m.find()) {
            int start = m.start();
            int end = m.end();
            String type = m.group(1);
            String name = m.group(2);
            return new Object[]{start, end, type, name};
        }
        return null;
    }


    private String replaceFieldType(String text, int start, int end, String oldType, String newType) {
        String decl = text.substring(start, end);

        decl = decl.replaceFirst("\\b" + java.util.regex.Pattern.quote(oldType) + "\\b", newType);
        decl = decl.replaceFirst("new\\s+" + java.util.regex.Pattern.quote(oldType) + "\\b", "new " + newType);
        return text.substring(0, start) + decl + text.substring(end);
    }

}
