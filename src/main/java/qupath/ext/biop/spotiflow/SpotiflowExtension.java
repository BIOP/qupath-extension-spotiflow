package qupath.ext.biop.spotiflow;

import javafx.beans.property.StringProperty;
import org.controlsfx.control.PropertySheet;
import org.controlsfx.control.action.Action;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.prefs.controlsfx.PropertyItemBuilder;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.tools.MenuTools;

import java.io.InputStream;
import java.util.LinkedHashMap;

/**
 * Install Spotiflow as an extension.
 * <p>
 * Installs Spotiflow into QuPath, adding some metadata and adds the necessary global variables to QuPath's Preferences
 *
 * @author Olivier Burri
 */
public class SpotiflowExtension implements QuPathExtension, GitHubProject {

    private static final Logger logger = LoggerFactory.getLogger(SpotiflowExtension.class);
    private boolean isInstalled = false;

    private static final LinkedHashMap<String, String> SCRIPTS = new LinkedHashMap<>() {{
       // put("Spotiflow training script template", "scripts/Spotiflow_training_template.groovy");
        put("Spotiflow detection script template", "scripts/Spotiflow_detection_template.groovy");
       // put("Detect nuclei and cells using Spotiflow.groovy", "scripts/Detect_nuclei_and_cells_using_Spotiflow.groovy");
       // put("Create Spotiflow training and validation images", "scripts/Create_Spotiflow_training_and_validation_images.groovy");
    }};

    @Override
    public GitHubRepo getRepository() {
        return GitHubRepo.create("Spotiflow QuPath Extension", "biop", "qupath-extension-spotiflow");
    }

    @Override
    public void installExtension(QuPathGUI qupath) {
        if (isInstalled)
            return;

        SCRIPTS.entrySet().forEach(entry -> {
            String name = entry.getValue();
            String command = entry.getKey();
            try (InputStream stream = SpotiflowExtension.class.getClassLoader().getResourceAsStream(name)) {
                String script = new String(stream.readAllBytes(), "UTF-8");
                if (script != null) {
                    MenuTools.addMenuItems(
                            qupath.getMenu("Extensions>Spotiflow", true),
                            new Action(command, e -> openScript(qupath, script)));
                }
            } catch (Exception e) {
                logger.error(e.getLocalizedMessage(), e);
            }
        });
        // Get a copy of the spotiflow options
        SpotiflowSetup options = SpotiflowSetup.getInstance();


        // Create the options we need
        StringProperty spotiflowPath = PathPrefs.createPersistentPreference("spotiflowPythonPath", "");
        StringProperty condaPath = PathPrefs.createPersistentPreference("condaPath", "");

        //Set options to current values
        options.setSpotiflowPythonPath(spotiflowPath.get());
        options.setCondaPath(condaPath.get());

        // Listen for property changes
        spotiflowPath.addListener((v, o, n) -> options.setSpotiflowPythonPath(n));
        condaPath.addListener((v, o, n) -> options.setCondaPath(n));

        PropertySheet.Item spotiflowPathItem = new PropertyItemBuilder<>(spotiflowPath, String.class)
                .propertyType(PropertyItemBuilder.PropertyType.GENERAL)
                .name("Spotiflow 'python.exe' location")
                .category("Spotiflow")
                .description("Enter the full path to your spotiflow environment, including 'python.exe'\nDo not include quotes (\') or double quotes (\") around the path.")
                .build();

        PropertySheet.Item condaPathItem = new PropertyItemBuilder<>(condaPath, String.class)
                .propertyType(PropertyItemBuilder.PropertyType.GENERAL)
                .name("'Conda/Mamba' script location (optional)")
                .category("Spotiflow")
                .description("The full path to you conda/mamba command, in case you want the extension to use the 'conda activate' command.\ne.g 'C:\\ProgramData\\Miniconda3\\condabin\\mamba.bat'\nDo not include quotes (\') or double quotes (\") around the path.")
                .build();

        // Add Permanent Preferences and Populate Preferences
        QuPathGUI.getInstance().getPreferencePane().getPropertySheet().getItems().addAll(spotiflowPathItem, condaPathItem);

    }

    @Override
    public String getName() {
        return "BIOP Spotiflow extension";
    }

    @Override
    public String getDescription() {
        return "An extension that allows running a Spotiflow Virtual Environment within QuPath";
    }

    @Override
    public Version getQuPathVersion() {
        return QuPathExtension.super.getQuPathVersion();
    }

    private static void openScript(QuPathGUI qupath, String script) {
        var editor = qupath.getScriptEditor();
        if (editor == null) {
            logger.error("No script editor is available!");
            return;
        }
        qupath.getScriptEditor().showScript("Spotiflow detection", script);
    }
}