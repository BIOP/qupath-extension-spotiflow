package qupath.ext.biop.spotiflow;

import qupath.fx.dialogs.Dialogs;

import java.io.File;

public class SpotiflowSetup {
    private static final SpotiflowSetup instance = new SpotiflowSetup();
    private String spotiflowPythonPath = null;
    private String condaPath = null;

    public static SpotiflowSetup getInstance() {
        return instance;
    }

    public String getSpotiflowPythonPath() {
        return spotiflowPythonPath;
    }

    public void setSpotiflowPythonPath(String path) {
        checkPath( path );
        this.spotiflowPythonPath = path;
    }

    public void setCondaPath(String condaPath) {
        checkPath( condaPath );
        this.condaPath = condaPath; }

    public String getCondaPath() { return condaPath; }

    private void checkPath(String path) {
        // It should be a file and it should exist
        if(!path.trim().isEmpty()) {
            File toCheck = new File(path);
            if (!toCheck.exists())
                Dialogs.showWarningNotification("Spotiflow extension: Path not found", "The path to \"" + path + "\" does not exist or does not point to a valid file.");
        }
    }
}
