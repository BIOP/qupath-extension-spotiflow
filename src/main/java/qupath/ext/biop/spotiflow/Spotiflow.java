/*-
 * Copyright 2020-2021 BioImaging & Optics Platform BIOP, Ecole Polytechnique Fédérale de Lausanne
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package qupath.ext.biop.spotiflow;

import javafx.collections.ObservableList;
import org.apache.commons.io.FileUtils;
import org.controlsfx.tools.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.biop.cmd.VirtualEnvironmentRunner;
import qupath.lib.analysis.features.ObjectMeasurements;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.images.servers.TransformedServerBuilder;
import qupath.lib.images.writers.ome.OMEPyramidWriter;
import qupath.lib.images.writers.ome.zarr.OMEZarrWriter;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Spot detection based on the following method:
 * <pre>
 *   Albert Dominguez Mantes et al.
 *     "Spotiflow: accurate and efficient spot detection for fluorescence microscopy with deep stereographic flow regression"
 *   <i>Cold Spring Harbor Laboratory - bioRxiv</i>, 2024. doi: <a href=https://doi.org/10.1101/2024.02.01.578426>10.1101/2024.02.01.578426</a>
 * </pre>
 * See the main repo at <a href="https://github.com/weigertlab/spotiflow">https://github.com/weigertlab/spotiflow</a>
 * <p>
 * Very much inspired by qupath-extension-cellpose at <a href="https://github.com/BIOP/qupath-extension-cellpose">https://github.com/BIOP/qupath-extension-cellpose</a>
 * <p>
 *
 * @author Rémy Dornier
 */

public class Spotiflow {

    private final static Logger logger = LoggerFactory.getLogger(Spotiflow.class);

    // Parameters and parameter values that will be passed to the spotiflow command
    protected File modelDir;
    protected String pretrainedModelName;
    protected File tempDirectory;
    protected File trainingInputDir ;
    protected File trainingOutputDir;
    protected SpotiflowSetup spotiflowSetup = SpotiflowSetup.getInstance();
    protected LinkedHashMap<String, String> parameters;
    protected boolean cleanTempDir;
    protected boolean disableGPU;
    protected boolean process3d;
    protected double probabilityThreshold;
    protected int minDistance;
    protected Map<String, Integer> channels = new HashMap<>();
    protected String doSubpixel;
    protected String pathClass;
    protected boolean classChannelName;
    protected int nThreads;
    protected boolean isOmeZarr;

    private List<String> theLog = new ArrayList<>();
    private final String CSV_SEPARATOR = ",";
    private final String NAME_SEPARATOR = "_";
    private final String ZARR_FILE_EXTENSION = ".ome.zarr";
    private final String TIFF_FILE_EXTENSION = ".ome.tiff";
    private final String ALL_SLICES = "allZ";
    private File imageDirectory = null;

    /**
     * Create a builder to customize detection parameters.
     *
     * @return this builder
     */
    public static SpotiflowBuilder builder() {
        return new SpotiflowBuilder();
    }


    private PathObject objectToPoint(String channelClass, PixelCalibration cal, double x,
                                     double y, double z, int c, int t, double intensity, double probability) {
        ImagePlane imagePlane = ImagePlane.getPlaneWithChannel(c, (int) z, t);
        ROI pointROI = ROIs.createPointsROI(x, y, imagePlane);
        PathObject pointObject = PathObjects.createDetectionObject(pointROI);

        if(channelClass == null || channelClass.equals("null"))
            pointObject.resetPathClass();
        else pointObject.setPathClass(PathClass.fromString(channelClass));

        ObjectMeasurements.addShapeMeasurements(pointObject, cal);
        pointObject.getMeasurementList().put("Spotiflow intensity", intensity);
        pointObject.getMeasurementList().put("Spotiflow probability", probability);

        return pointObject;
    }

    /**
     * Optionally submit runnable to a thread pool. This limits the parallelization used by parallel streams.
     *
     * @param runnable The runnable to submit
     */
    private void runInPool(Runnable runnable) {
        if (nThreads > 0) {
            if (nThreads == 1)
                logger.info("Processing with {} thread", nThreads);
            else
                logger.info("Processing with {} threads", nThreads);
            // Using an outer thread poll impacts any parallel streams created inside
            var pool = new ForkJoinPool(nThreads);
            try {
                pool.submit(runnable);
            } finally {
                pool.shutdown();
                try {
                    pool.awaitTermination(2, TimeUnit.DAYS);
                } catch (InterruptedException e) {
                    logger.warn("Process was interrupted! {}", e.getLocalizedMessage(), e);
                }
            }
        } else {
            runnable.run();
        }
    }

    /**
     * Prints the help and the available CLI arguments for the training command
     */
    public void helpTrain(){
        printHelp("spotiflow-train");
    }

    /**
     * Prints the help and the available CLI arguments for the prediction command
     */
    public void helpPredict(){
       printHelp("spotiflow-predict");
    }

    /**
     * Prints the help and the available CLI arguments for the given command
     * @param command
     */
    private void printHelp(String command){
        try {
            // Need to define the name of the command we are running.
            VirtualEnvironmentRunner veRunner = getVirtualEnvironmentRunner(command);

            // This is the list of commands after the 'python' call
            // We want to ignore all warnings to make sure the log is clean (-W ignore)
            // We want to be able to call the module by name (-m)
            // We want to make sure UTF8 mode is by default (-X utf8)
            List<String> spotiflowArguments = new ArrayList<>();//(Arrays.asList("-Xutf8", "-W", "ignore", "-m"));

            //TODO set it as for cellpose when available
            //spotiflowArguments.add(command);
            spotiflowArguments.add("--help");

            veRunner.setArguments(spotiflowArguments);

            // Finally, we can run the help
            veRunner.runCommand(true);
        } catch (IOException e) {
            logger.error("Failed to run help", e);
        }
    }

    /**
     * Detect cells within one or more parent objects, firing update events upon completion.
     *
     * @param imageData the image data containing the object
     * @param parents   the parent objects; existing child objects will be removed, and replaced by the detected cells
     */
    public void detectObjects(ImageData<BufferedImage> imageData, String imageName, Collection<? extends PathObject> parents) {
        runInPool(() -> detectObjectsImpl(imageData, imageName, parents));
    }

    /**
     * Detect cells within one or more parent objects, firing update events upon completion.
     *
     * @param imageData the image data containing the object
     * @param parents   the parent objects; existing child objects will be removed, and replaced by the detected cells
     */
    private void detectObjectsImpl(ImageData<BufferedImage> imageData, String imageName, Collection<? extends PathObject> parents) {

        Objects.requireNonNull(parents);

        PixelCalibration cal = imageData.getServer().getPixelCalibration();
        int nZ = imageData.getServer().nZSlices();

        if(cleanTempDir) {
            cleanDirectory(tempDirectory);
        }
        String fileExtension;
        if(isOmeZarr) {
            fileExtension = ZARR_FILE_EXTENSION;
        } else {
            fileExtension = TIFF_FILE_EXTENSION;
        }

        this.imageDirectory = new File(tempDirectory, imageName);
        this.imageDirectory = process3d ? new File(this.imageDirectory, "3D") : this.imageDirectory;
        if(!this.imageDirectory.exists()) {
            this.imageDirectory.mkdirs();
        }

        // clear previous detections
        parents.forEach(PathObject::clearChildObjects);

        // loop over the different channels to process
        Map<String, Map<String, PathObject>> channelCorrespondanceMap = new HashMap<>();
        for(String channel: channels.keySet()) {
            logger.info("Working on channel {}", channel);
            Collection<PathObject> missingParents = new ArrayList<>();
            Map<String, PathObject> correspondanceMap = new HashMap<>();
            
            if (cleanTempDir) {
                missingParents = (Collection<PathObject>) parents;
            } else {
                for (PathObject parent : parents) {
                    ROI region = parent.getROI();
                    String name = channel + NAME_SEPARATOR +
                            (int) region.getBoundsX() + NAME_SEPARATOR +
                            (int) region.getBoundsY() + NAME_SEPARATOR +
                            (int) region.getBoundsWidth() + NAME_SEPARATOR +
                            (int) region.getBoundsHeight() + NAME_SEPARATOR;

                    name += process3d ? ALL_SLICES : parent.getROI().getZ();

                    File optFile = new File(this.imageDirectory, name + fileExtension);
                    if (optFile.exists()) {
                        logger.info("The parent shape '{}' is already saved ; skip saving it again", name);
                        correspondanceMap.put(name, parent);
                    } else {
                        logger.warn("The parent shape '{}' is missing. Will be saved anyway", name);
                        missingParents.add(parent);
                    }
                }
            }

            // save images in temp folder
            for (PathObject parent : missingParents) {


                RegionRequest region = RegionRequest.createInstance(imageData.getServerPath(), 1.0, parent.getROI());

                String name = channel + NAME_SEPARATOR +
                        region.getX() + NAME_SEPARATOR +
                        region.getY() + NAME_SEPARATOR +
                        region.getWidth() + NAME_SEPARATOR +
                        region.getHeight() + NAME_SEPARATOR;

                int currentSlice = parent.getROI().getZ();
                name += process3d ? ALL_SLICES : currentSlice;

                correspondanceMap.put(name, parent);
                String outputPath = new File(this.imageDirectory, name + fileExtension).getAbsolutePath();

                // create a new ImageServer containing only the channel of interest
                ImageServer<BufferedImage> selectedChannels = new TransformedServerBuilder(imageData.getServer())
                        .extractChannels(channels.get(channel))
                        .build();
                ImageData<BufferedImage> selectedData = new ImageData<>(selectedChannels);

                if(isOmeZarr) {
                    // write the ome.zarr
                    logger.info("Saving image(s) into the temporary folder as OME-Zarr");
                    OMEZarrWriter.Builder builder = new OMEZarrWriter.Builder(selectedData.getServer());
                    builder.parallelize(nThreads)
                            .tileSize(512)
                            .region(region)
                            .downsamples(1, 2);

                    // process all slices
                    if (!process3d)
                        builder.zSlices(currentSlice, currentSlice + 1);

                    // save ome-zarr
                    try (OMEZarrWriter omeZarrWriter = builder.build(outputPath)) {
                        omeZarrWriter.writeImage();
                    } catch (Exception e) {
                        logger.error("Error during writing OME-Zarr file", e);
                    }
                }else{
                    // write the ome.tiff
                    logger.info("Saving image(s) into the temporary folder as OME-Tiff");
                    OMEPyramidWriter.Builder builder = new OMEPyramidWriter.Builder(imageData.getServer());
                    builder.parallelize()
                            .tileSize(512)
                            .region(region)
                            .scaledDownsampling(1, 2)
                            .channels(channels.get(channel));

                    // process all slices
                    if(process3d)
                        builder.allZSlices();

                    // save ome-tiff
                    try {
                        builder.build().writeSeries(outputPath);
                    } catch (Exception e) {
                        logger.error("Error during writing OME-TIFF file", e);
                    }
                }
            }
            channelCorrespondanceMap.put(channel, correspondanceMap);
        }
        
        // run spotiflow
        try {
            logger.info("Running Spotiflow");
            runSpotiflow();
        } catch (IOException | InterruptedException e) {
            logger.error("Failed to Run Spotiflow", e);
            return;
        }
            
        for(String channel: channels.keySet()) {
            Map<String, PathObject> correspondanceMap = channelCorrespondanceMap.get(channel);
            
            // create new channel PathClass if it doesn't already exist
            String detectionClass = this.pathClass;
            if(this.classChannelName){
                detectionClass = channel;
            }

            ObservableList<PathClass> availablePathClasses = QPEx.getQuPath().getAvailablePathClasses();
            PathClass channelPathClass = PathClass.fromString(detectionClass);
            if (!availablePathClasses.contains(channelPathClass)) {
                availablePathClasses.add(channelPathClass);
                QPEx.getQuPath().getProject().setPathClasses(availablePathClasses);
            }

            Map<Integer, PathObject> annotationZMap = new HashMap<>();
            Map<PathObject, List<PathObject>> annotationChildMap = new HashMap<>();

            // read results, add points and add measurements
            for (String name : correspondanceMap.keySet()) {
                File detectionFile = findResultFile(name);

                if (detectionFile.exists()) {
                    String[] regionAttributes = name.split(NAME_SEPARATOR);
                    double x0 = Double.parseDouble(regionAttributes[1]);
                    double y0 = Double.parseDouble(regionAttributes[2]);
                    PathObject parent = correspondanceMap.get(name);
                    ROI parentROI = parent.getROI();

                    try {
                        List<String> detectionList = Files.readAllLines(detectionFile.toPath());
                        int pos = process3d ? 1 : 0;
                        ImagePlane parentPlane = parent.getROI().getImagePlane();
                        annotationZMap.put(parentPlane.getZ(), parent);

                        // duplicate current annotation across the different Z
                        if(process3d){
                            for(int z = 0; z < nZ; z++){
                                if(z == parentPlane.getZ())
                                    continue;
                                ROI roi = GeometryTools.geometryToROI(parent.getROI().getGeometry(),
                                        ImagePlane.getPlaneWithChannel(parentPlane.getC(), z, parentPlane.getT()));
                                PathObject duplicatedPathObject = PathObjects.createAnnotationObject(roi, parent.getPathClass());
                                duplicatedPathObject.setName(parent.getName());
                                imageData.getHierarchy().addObject(duplicatedPathObject);
                                annotationZMap.put(z, duplicatedPathObject);
                            }
                        }

                        //skip the header and loop over the detections
                        for (int d = 1; d < detectionList.size(); d++) {
                            String detection = detectionList.get(d);
                            String[] attributes = detection.split(CSV_SEPARATOR);
                            double zf = process3d ? Double.parseDouble(attributes[0]) : parentPlane.getZ();
                            double yf = Double.parseDouble(attributes[pos]) + y0;
                            double xf = Double.parseDouble(attributes[pos+1]) + x0;
                            double intensity = Double.parseDouble(attributes[pos+2]);
                            double probability = Double.parseDouble(attributes[pos+3]);

                            if(parentROI.contains(xf, yf)) {
                                // populate the list of child according to the current Z
                                List<PathObject> child;
                                PathObject parentZAnnotation = annotationZMap.get(((int) zf));
                                if (annotationChildMap.containsKey(parentZAnnotation)) {
                                    child = annotationChildMap.get(parentZAnnotation);
                                } else {
                                    child = new ArrayList<>();
                                }
                                child.add(objectToPoint(detectionClass, cal, xf, yf, zf, parentPlane.getC(),
                                        parentPlane.getT(), intensity, probability));
                                annotationChildMap.put(parentZAnnotation, child);
                            }
                        }

                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }

            // Assign the objects to the parent object
            for(PathObject parentZAnnotation: annotationChildMap.keySet()){
                parentZAnnotation.setLocked(true);
                parentZAnnotation.addChildObjects(annotationChildMap.get(parentZAnnotation));
            }

            // update hierarchy to show the objects
            imageData.getHierarchy().fireHierarchyChangedEvent(this);
        }
    }

    private File findResultFile(String name){
       List<File> candidateResultsFile = Arrays.stream(Objects.requireNonNull(this.imageDirectory.listFiles()))
                .filter(e -> e.getName().contains(name) && e.getName().endsWith(".csv"))
                .collect(Collectors.toList());
       if(candidateResultsFile.isEmpty()) {
           return new File("");
       }else{
           return candidateResultsFile.getFirst();
       }
    }


    private void cleanDirectory(File directory) {
        // Delete the existing directory
        try {
            FileUtils.deleteDirectory(directory);
        } catch (IOException e) {
            logger.error("Failed to delete temp directory", e);
        }

        // Recreate the directory
        try {
            FileUtils.forceMkdir(directory);
        } catch (IOException e) {
            logger.error("Failed to create temp directory", e);
        }
    }

    /**
     * Selects the right folder to run from, based on whether it's cellpose or omnipose.
     * Hopefully this will become deprecated soon
     *
     * @return the virtual environment runner that can run the desired command
     */
    private VirtualEnvironmentRunner getVirtualEnvironmentRunner(String command) {

        // Make sure that spotiflowSetup.getSpotiflowPythonPath() is not empty
        if (spotiflowSetup.getSpotiflowPythonPath().isEmpty()) {
            throw new IllegalStateException("Spotiflow python path is empty. Please set it in Edit > Preferences");
        }

        // Change the envType based on the setup options
        VirtualEnvironmentRunner.EnvType type = VirtualEnvironmentRunner.EnvType.EXE;
        String condaPath = null;
        if (!spotiflowSetup.getCondaPath().isEmpty()) {
            type = VirtualEnvironmentRunner.EnvType.CONDA;
            condaPath = spotiflowSetup.getCondaPath();
        }

        // Set python executable in the environment
        String pythonPath = spotiflowSetup.getSpotiflowPythonPath();

        switch (Platform.getCurrent()) {
            case UNIX:
            case OSX:
                pythonPath = new File(pythonPath).getParent() + File.separator + command;
                break;
            case WINDOWS:
            default:
                pythonPath = new File(pythonPath).getParent() + File.separator + "Scripts" + File.separator + command + ".exe";
                break;
        }

        return new VirtualEnvironmentRunner(pythonPath, type, condaPath, this.getClass().getSimpleName());
    }

    /**
     * This class actually runs Spotiflow by calling the virtual environment
     *
     * @throws IOException          Exception in case files could not be read
     * @throws InterruptedException Exception in case of command thread has some failing
     */
    private void runSpotiflow() throws InterruptedException, IOException {
        // Need to define the name of the command we are running.
        VirtualEnvironmentRunner veRunner = getVirtualEnvironmentRunner("spotiflow-predict");

        // This is the list of commands after the 'python' call
        List<String> spotiflowArguments = new ArrayList<>();

        spotiflowArguments.add(this.imageDirectory.getAbsolutePath());
        spotiflowArguments.add("--out-dir");
        spotiflowArguments.add(this.imageDirectory.getAbsolutePath());
        spotiflowArguments.add("--verbose");

        if(this.isOmeZarr) {
            spotiflowArguments.add("--zarr-component");
            spotiflowArguments.add("s0");
        }
        if(this.pretrainedModelName != null) {
            spotiflowArguments.add("--pretrained-model");
            spotiflowArguments.add(this.pretrainedModelName);
        }
        if(this.modelDir != null) {
            spotiflowArguments.add("--model-dir");
            spotiflowArguments.add(this.modelDir.getAbsolutePath());
        }
        if(this.probabilityThreshold > 0){
            spotiflowArguments.add("--probability-threshold");
            spotiflowArguments.add(String.valueOf(this.probabilityThreshold));
        }
        if(this.minDistance > 0){
            spotiflowArguments.add("--min-distance");
            spotiflowArguments.add(String.valueOf(this.minDistance));
        }
        if(!this.doSubpixel.equals("None")){
            spotiflowArguments.add("--subpix");
            spotiflowArguments.add(this.doSubpixel);
        }

        spotiflowArguments.add("--device");
        if(this.disableGPU){
            spotiflowArguments.add("cpu");
        } else {
            spotiflowArguments.add("auto");
        }

        this.parameters.forEach((parameter, value) -> {
            spotiflowArguments.add("--" + parameter);
            if (value != null) {
                spotiflowArguments.add(value);
            }
        });

        veRunner.setArguments(spotiflowArguments);

        // Finally, we can run Spotiflow
        veRunner.runCommand(true);

        // Add a waitFor() here to fix MAC Mx chip killing thread issue (#15)
        veRunner.getProcess().waitFor();
    }

    /**
     * Executes the spotiflow training by
     * 1. Saving the images
     * 2. running spotiflow
     * 3. moving the resulting model file to the desired directory
     *
     * @return a link to the model file, which can be displayed
     */
    /*public File train() {

        try {
            if (this.trainingInputDir == null) {
                logger.error("You need to set the input folder for training 'builder.setTrainingInputDir()'");
                throw new RuntimeException();
            }
            if (this.trainingOutputDir == null) {
                logger.error("You need to set the output folder for training 'builder.setTrainingOutputDir()'");
                throw new RuntimeException();
            }
            runTraining();

//            this.modelFile = moveRenameAndReturnModelFile();
//
//            // Get the training results before overwriting the log with a new run
//            this.trainingResults = parseTrainingResults();
//
//            // Get cellpose masks from the validation
//            runCellposeOnValidationImages();
//
//            this.qcResults = runCellposeQC();
//
//            return modelFile;

        } catch (IOException | InterruptedException e) {
            logger.error("Error while running spotiflow training: {}", e.getMessage(), e);
        }
        return null;
    }*/

    /**
     * Configures and runs the {@link VirtualEnvironmentRunner} that will ultimately run spotiflow training
     *
     * @throws IOException          Exception in case files could not be read
     * @throws InterruptedException Exception in case of command thread has some failing
     */
    private void runTraining() throws IOException, InterruptedException {
        VirtualEnvironmentRunner veRunner = getVirtualEnvironmentRunner("spotiflow-train");

        // This is the list of commands after the 'python' call
        List<String> spotiflowArguments = new ArrayList<>();

        spotiflowArguments.add(this.trainingInputDir.getAbsolutePath());
        spotiflowArguments.add("--out-dir");
        spotiflowArguments.add(this.trainingOutputDir.getAbsolutePath());

        this.parameters.forEach((parameter, value) -> {
            spotiflowArguments.add("--" + parameter);
            if (value != null) {
                spotiflowArguments.add(value);
            }
        });

        veRunner.setArguments(spotiflowArguments);

        // Finally, we can run Spotiflow
        veRunner.runCommand(true);

        // Get the log
        this.theLog = veRunner.getProcessLog();
    }
}