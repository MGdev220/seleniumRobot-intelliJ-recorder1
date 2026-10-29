package io.github.bhecquet.seleniumRobot.recorder;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static com.intellij.ui.popup.PopupComponent.LOG;

public class PageClassManager {

    private final Project project;

    public PageClassManager(Project project) {
        this.project = project;
    }

    public String buildClassName(String pageMethodName) {
        if (pageMethodName == null || pageMethodName.isBlank()) {
            return "NewPage";
        }

        String normalizedName = pageMethodName.trim();

        if (!normalizedName.endsWith("Page")) {
            normalizedName += "Page";
        }

        return Character.toUpperCase(normalizedName.charAt(0)) + normalizedName.substring(1);
    }

    public String buildFileName(String pageMethodName) {
        return buildClassName(pageMethodName) + ".java";
    }

    public String buildMethodCall(String pageMethodName, int visitNumber) {
        return buildClassName(pageMethodName) + "." + buildVisitMethodName(visitNumber) + "();";
    }

    public String buildMethodCall(String pageMethodName) {
        return buildClassName(pageMethodName) + ".execute();";
    }


    public String buildVisitMethodName(int visitNumber) {
        if (visitNumber <= 1) {
            return "execute";
        }

        return "execute" + visitNumber;
    }


    public String buildClassContent(String packageName, String pageMethodName) {
        String className = buildClassName(pageMethodName);
        String packageDeclaration = packageName == null || packageName.isBlank()
                ? ""
                : "package " + packageName + ";\n\n";

        return packageDeclaration
                + "public class " + className + " {\n\n"
                + "    public static void execute() {\n\n"
                + "    }\n"
                + "}\n";
    }

    public VirtualFile createOrFindPageFile(
            VirtualFile sourceDirectory,
            String packageName,
            String pageMethodName
    ) {
        if (sourceDirectory == null || !sourceDirectory.isDirectory()) {
            throw new IllegalArgumentException("Le dossier source est invalide");
        }

        String fileName = buildFileName(pageMethodName);
        VirtualFile existingFile = sourceDirectory.findChild(fileName);

        if (existingFile != null) {
            System.out.println("[Recorder] Page Object  exist already: " + existingFile.getPath());
            return existingFile;
        }

        AtomicReference<VirtualFile> createdFile = new AtomicReference<>();

        AtomicReference<Throwable> creationError = new AtomicReference<>();

        Runnable creationTask = () ->
                ApplicationManager.getApplication().runWriteAction(() -> {
                    try {
                        System.out.println("[Recorder] Creation of file  : " + sourceDirectory.getPath() + "/" + fileName);

                        VirtualFile file = sourceDirectory.createChildData(this, fileName);
                        VfsUtil.saveText(file, buildClassContent(packageName, pageMethodName));

                        file.refresh(false, false);
                        sourceDirectory.refresh(false, true);
                        createdFile.set(file);
                    } catch (Throwable error) {
                        creationError.set(error);
                    }
                });

        if (ApplicationManager.getApplication().isDispatchThread()) {
            creationTask.run();
        } else {
            ApplicationManager.getApplication()
                    .invokeAndWait(creationTask);
        }

        if (creationError.get() != null) {
            throw new IllegalStateException("Impossible de créer le fichier " + fileName + " dans " + sourceDirectory.getPath(), creationError.get()
            );
        }

        if (createdFile.get() == null) {
            throw new IllegalStateException("Le fichier " + fileName + " n’a pas été créé");
        }

        return createdFile.get();
    }

    public Editor openPageEditor(VirtualFile pageFile) {
        return openPageEditor(pageFile, 1);
    }

    public Editor openPageEditor(
            VirtualFile pageFile,
            int visitNumber
    ) {
        if (project == null) {
            throw new IllegalStateException(
                    "Le projet IntelliJ est obligatoire"
            );
        }

        if (pageFile == null || pageFile.isDirectory()) {
            throw new IllegalArgumentException(
                    "Le fichier Page Object est invalide"
            );
        }

        String methodName = buildVisitMethodName(visitNumber);
        createVisitMethodIfMissing(pageFile, visitNumber);

        AtomicReference<Editor> editorReference = new AtomicReference<>();
        AtomicReference<Throwable> openingError = new AtomicReference<>();

        Runnable openingTask = () -> {
            try {
                int bodyOffset = findMethodBodyOffset(
                        readFileContent(pageFile),
                        methodName
                );

                OpenFileDescriptor descriptor = new OpenFileDescriptor(
                        project,
                        pageFile,
                        bodyOffset
                );

                Editor editor = FileEditorManager
                        .getInstance(project)
                        .openTextEditor(descriptor, true);

                if (editor == null) {
                    throw new IllegalStateException(
                            "Impossible d'ouvrir " + pageFile.getName()
                    );
                }

                editor.getCaretModel().moveToOffset(bodyOffset);
                editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
                editorReference.set(editor);
            } catch (Throwable error) {
                openingError.set(error);
            }
        };

        if (ApplicationManager.getApplication().isDispatchThread()) {
            openingTask.run();
        } else {
            ApplicationManager.getApplication().invokeAndWait(openingTask);
        }

        if (openingError.get() != null) {
            throw new IllegalStateException(
                    "Impossible d'ouvrir " + methodName + "() dans " + pageFile.getPath(),
                    openingError.get()
            );
        }

        if (editorReference.get() == null) {
            throw new IllegalStateException(
                    "Aucun editeur obtenu pour " + pageFile.getPath()
            );
        }

        return editorReference.get();
    }

    public int findExecuteBodyOffset(String content) {
        return findMethodBodyOffset(
                content,
                "execute"
        );
    }

    public int findMethodBodyOffset(
            String content,
            String methodName
    ) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException(" Java content is empty");
        }

        String signature = "public static void " + methodName + "()";
        int methodStart = content.indexOf(signature);

        if (methodStart < 0) {
            throw new IllegalStateException("Method " + methodName + "() is abscent");
        }

        int openingBrace = content.indexOf('{', methodStart);

        if (openingBrace < 0) {
            throw new IllegalStateException("the body of " + methodName + "() is invalid"
            );
        }

        int offset = openingBrace + 1;

        while (offset < content.length()
                && (content.charAt(offset) == '\r'
                || content.charAt(offset) == '\n')) {
            offset++;
        }

        return offset;
    }

    public void createVisitMethodIfMissing(
            VirtualFile pageFile,
            int visitNumber
    ) {
        String methodName = buildVisitMethodName(visitNumber);

        if ("execute".equals(methodName)) {
            return;
        }

        AtomicReference<Throwable> creationError =
                new AtomicReference<>();

        Runnable creationTask = () -> {
            try {
                Document document = FileDocumentManager
                        .getInstance()
                        .getDocument(pageFile);

                if (document == null) {
                    throw new IllegalStateException(
                            "Impossible d'obtenir le document IntelliJ pour "
                                    + pageFile.getName()
                    );
                }

                String signature =
                        "public static void "
                                + methodName
                                + "()";

                if (document.getText().contains(signature)) {
                    return;
                }

                int classClosingBrace =
                        document.getText().lastIndexOf('}');

                if (classClosingBrace < 0) {
                    throw new IllegalStateException(
                            "La classe Java est invalide : "
                                    + pageFile.getName()
                    );
                }

                String newMethod =
                        "\n"
                                + "    public static void "
                                + methodName
                                + "() {\n"
                                + "\n"
                                + "    }\n";

                WriteCommandAction.runWriteCommandAction(
                        project,
                        () -> document.insertString(
                                classClosingBrace,
                                newMethod
                        )
                );

                LOG.info("[Recorder] Méthode créée : " + methodName + "() dans " + pageFile.getPath()
                );
            } catch (Throwable error) {
                creationError.set(error);
            }
        };

        if (ApplicationManager
                .getApplication()
                .isDispatchThread()) {
            creationTask.run();
        } else {
            ApplicationManager
                    .getApplication()
                    .invokeAndWait(creationTask);
        }

        if (creationError.get() != null) {
            throw new IllegalStateException(
                    "Impossible de créer la méthode "
                            + methodName
                            + " dans "
                            + pageFile.getName(),
                    creationError.get()
            );
        }
    }

    private String readFileContent(VirtualFile file) {
        Document document = FileDocumentManager
                .getInstance()
                .getDocument(file);

        if (document != null) {
            return document.getText();
        }

        try {
            return VfsUtilCore.loadText(file);
        } catch (IOException error) {
            throw new IllegalStateException(
                    "Impossible de lire " + file.getName(),
                    error
            );
        }
    }

    public Project getProject() {
        return project;
    }
}