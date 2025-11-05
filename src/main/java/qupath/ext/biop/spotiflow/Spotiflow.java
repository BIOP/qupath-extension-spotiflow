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

import ij.measure.ResultsTable;
import javafx.collections.ObservableList;
import org.apache.commons.io.FileUtils;
import org.controlsfx.tools.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.biop.cmd.VirtualEnvironmentRunner;
import qupath.lib.analysis.features.ObjectMeasurements;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.*;
import qupath.lib.images.writers.ome.OMEPyramidWriter;
import qupath.lib.images.writers.ome.zarr.OMEZarrWriter;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.PointsROI;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.RectangleROI;
import qupath.lib.roi.interfaces.ROI;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.function.Function;
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

    // parameters for prediction only
    protected File modelDir;
    protected String pretrainedModelName;
    protected String doSubpixel;
    protected String pathClass;
    protected double probabilityThreshold;
    protected int minDistance;
    protected boolean classChannelName;
    protected boolean clearAllChildObjects;
    protected boolean clearChildObjectsBelongingToCurrentChannels;
    private File imageDirectory = null;

    // parameters for both prediction and training
    protected SpotiflowSetup spotiflowSetup = SpotiflowSetup.getInstance();
    protected LinkedHashMap<String, String> parameters;
    protected File tempDirectory;
    protected Map<String, Integer> channels = new HashMap<>();
    protected String[] channelsIdx;
    protected boolean cleanTempDir;
    protected boolean disableGPU;
    protected boolean process3d;
    protected int nThreads;
    protected boolean isOmeZarr;

    // parameters for training only
    protected File trainingInputDir ;
    protected File trainingOutputDir;
    protected File validationInputDir;
    protected File testDir;
    protected int nEpochs;
    protected boolean doNotApplyDataAugmentation;
    protected String modelToFineTune;
    protected double lr;
    protected boolean includeNegatives;
    protected List<String> pointClasses;
    protected int zStart;
    protected int zEnd;
    protected boolean cleanTrainingDir;
    protected Function<ROI, PathObject> creatorFun;
    private int zCurrentEnd;
    private ResultsTable qcResults;

    // constants
    private final String CSV_SEPARATOR = ",";
    private final String NAME_SEPARATOR = "_";
    private final String Z_SEPARATOR = "-";
    private final String ZARR_FILE_EXTENSION = ".ome.zarr";
    private final String TIFF_FILE_EXTENSION = ".ome.tiff";

    /**
     * Create a builder to customize detection parameters.
     *
     * @return this builder
     */
    public static SpotiflowBuilder builder() {
        return new SpotiflowBuilder();
    }

    /**
     * Load a previously serialized builder.
     * See {@link SpotiflowBuilder#SpotiflowBuilder(File)} and {@link SpotiflowBuilder#saveBuilder(String)}
     *
     * @param builderPath path to the builder JSON file.
     * @return this builder
     */
    public static SpotiflowBuilder builder(File builderPath) {
        return new SpotiflowBuilder(builderPath);
    }

    private PathObject objectToPoint(String channelClass, PixelCalibration cal, double x,
                                     double y, double z, int c, int t, double intensity, double probability) {
        ImagePlane imagePlane = ImagePlane.getPlaneWithChannel(c, (int) z, t);
        ROI pointROI = ROIs.createPointsROI(x, y, imagePlane);
        PathObject pointObject = this.creatorFun.apply(pointROI);

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
            List<String> spotiflowArguments = new ArrayList<>();

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
        if(process3d) {
            zCurrentEnd = zEnd;
            if (zCurrentEnd < 0) {
                zCurrentEnd = nZ - 1;
            }
            if (zCurrentEnd < zStart) {
                logger.warn("Z Positions are not valid: start: {} > end: {}. Take all slices instead.", zStart, zCurrentEnd);
                zStart = 0;
                zCurrentEnd = nZ - 1;
            }
        }

        // get the channels idx and name
        try{
            Integer[] channelsIntIdx = new Integer[channelsIdx.length];
            for(int i = 0; i < channelsIdx.length; i++){
                channelsIntIdx[i] = (Integer.parseInt(channelsIdx[i]));
            }
            setupChannels(imageData, channelsIntIdx);
        }catch (Exception e){
            setupChannels(imageData, channelsIdx);
        }

        if(cleanTempDir) {
            cleanDirectory(tempDirectory);
        }
        String fileExtension;
        if(isOmeZarr) {
            fileExtension = ZARR_FILE_EXTENSION;
        } else {
            fileExtension = TIFF_FILE_EXTENSION;
        }

        this.imageDirectory = new File(tempDirectory, imageName.replace(",", ""));
        this.imageDirectory = process3d ? new File(this.imageDirectory, "3D") : this.imageDirectory;
        if(!this.imageDirectory.exists()) {
            this.imageDirectory.mkdirs();
        }

        // clear all detections that belong to the current selected channel(s)
        if(this.clearChildObjectsBelongingToCurrentChannels){
            Set<String> channelNames = channels.keySet();
            logger.info("Clearing all child objects with the following class(es) : {}", channelNames);
            List<PathObject> childToDelete = new ArrayList<>();
            for (PathObject parent : parents) {
                for (PathObject childObject : parent.getChildObjects()) {
                    if(channelNames.contains(childObject.getPathClass().getName())){
                        childToDelete.add(childObject);
                    }
                }
            }
            imageData.getHierarchy().removeObjects(childToDelete, false);
        }

        // clear previous detections
        if(this.clearAllChildObjects) {
            logger.info("Clearing all child objects");
            parents.forEach(PathObject::clearChildObjects);
        }

        // create new channel PathClass if it doesn't already exist
        javafx.application.Platform.runLater(()-> {
            ObservableList<PathClass> availablePathClasses = QPEx.getQuPath().getAvailablePathClasses();
            if (this.classChannelName) {
                for (String channel : this.channels.keySet()) {
                    PathClass channelPathClass = PathClass.fromString(channel);
                    if (!availablePathClasses.contains(channelPathClass)) {
                        availablePathClasses.add(channelPathClass);
                    }
                }
            } else {
                PathClass channelPathClass = PathClass.fromString(this.pathClass);
                if (!availablePathClasses.contains(channelPathClass)) {
                    availablePathClasses.add(channelPathClass);
                }
            }
            QPEx.getQuPath().getProject().setPathClasses(availablePathClasses);
        });

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

                    name += "z" + (process3d ?  zStart + Z_SEPARATOR + zCurrentEnd : parent.getROI().getZ());

                    File optFile = new File(this.imageDirectory, name + fileExtension);
                    if (optFile.exists()) {
                        logger.info("The parent shape '{}' is already saved ; skip saving it again", name);
                        correspondanceMap.put(name, parent);
                    } else {
                        logger.info("The parent shape '{}' is missing. Will be saved anyway", name);
                        missingParents.add(parent);
                    }
                }
            }

            // save images in temp folder
            for (PathObject parent : missingParents) {
                String name = saveImage(imageData, this.imageDirectory, parent, channel, fileExtension);
                correspondanceMap.put(name, parent);
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
                            double zf = process3d ? Double.parseDouble(attributes[0]) + zStart : parentPlane.getZ();
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


    /**
     * Save the image corresponding to the bounding box of the selected region.
     * File format can be either ome.tiff or ome.zarr.
     *
     * @param imageData      the current imageData
     * @param outputDir      the output directory where to save the image
     * @param parent         the parent annotation from which to extract the bounding box
     * @param channel        the current channel name
     * @param fileExtension  the file format to use (ome.tiff or ome.zarr)
     * @param prefixName     prefix to add to the final image name. Empty or null if no prefix
     *
     * @return the final image name without the extension
     */
    private String saveImage(ImageData<BufferedImage> imageData, File outputDir, PathObject parent,
                             String channel, String fileExtension, String prefixName){
        RegionRequest region = RegionRequest.createInstance(imageData.getServerPath(), 1.0, parent.getROI());

        if(prefixName == null || prefixName.isEmpty())
            prefixName = "";
        else prefixName = prefixName + NAME_SEPARATOR;

        String name = prefixName + channel + NAME_SEPARATOR +
                region.getX() + NAME_SEPARATOR +
                region.getY() + NAME_SEPARATOR +
                region.getWidth() + NAME_SEPARATOR +
                region.getHeight() + NAME_SEPARATOR;

        int currentSlice = parent.getROI().getZ();

        name += "z" + (process3d ? zStart + Z_SEPARATOR + zCurrentEnd : currentSlice);

        String outputPath = new File(outputDir, name + fileExtension).getAbsolutePath();

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
            else
                builder.zSlices(zStart, zCurrentEnd + 1);

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
                builder.zSlices(zStart, zCurrentEnd + 1);

            // save ome-tiff
            try {
                builder.build().writeSeries(outputPath);
            } catch (Exception e) {
                logger.error("Error during writing OME-TIFF file", e);
            }
        }
        return name;
    }

    /**
     * Save the image corresponding to the bounding box of the selected region.
     * File format can be either ome.tiff or ome.zarr.
     *
     * @param imageData      the current imageData
     * @param outputDir      the output directory where to save the image
     * @param parent         the parent annotation from which to extract the bounding box
     * @param channel        the current channel name
     * @param fileExtension  the file format to use (ome.tiff or ome.zarr)
     *
     * @return the final image name without the extension
     */
    private String saveImage(ImageData<BufferedImage> imageData, File outputDir, PathObject parent, String channel, String fileExtension){
        return saveImage(imageData, outputDir, parent, channel, fileExtension, "");
    }

    /**
     * Get, from the output folder, the csv file corresponding to current analysis.
     *
     * @param name the name of the results file
     * @return
     */
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

        if(command != null && !command.isEmpty()) {
            pythonPath = switch (Platform.getCurrent()) {
                case UNIX, OSX -> new File(pythonPath).getParent() + File.separator + command;
                default ->
                        new File(pythonPath).getParent() + File.separator + "Scripts" + File.separator + command + ".exe";
            };
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
     *
     * @return a link to the model file, which can be displayed
     */
    public File train() {
        try {
            if (this.trainingInputDir == null) {
                logger.error("You need to set the input folder for training 'builder.setTrainingInputDir()'");
                throw new RuntimeException();
            }
            if (this.trainingOutputDir == null) {
                logger.error("You need to set the output folder for training 'builder.setTrainingOutputDir()'");
                throw new RuntimeException();
            }

            // get the channels idx and name
            try{
                Integer[] channelsIntIdx = new Integer[channelsIdx.length];
                for(int i = 0; i < channelsIdx.length; i++){
                    channelsIntIdx[i] = (Integer.parseInt(channelsIdx[i]));
                }
                setupChannels(QPEx.getQuPath().getProject().getImageList().getFirst().readImageData(), channelsIntIdx);
            }catch (Exception e){
                setupChannels(QPEx.getQuPath().getProject().getImageList().getFirst().readImageData(), channelsIdx);
            }

            if(this.cleanTrainingDir){
                // Clear a previous training
                cleanDirectory(this.trainingInputDir);
                cleanDirectory(this.validationInputDir);
                cleanDirectory(this.testDir);

                // save images
                saveTrainingImages();
            }

            // create the model directory
            this.modelDir = new File(this.trainingOutputDir, "model");
            this.modelDir.mkdirs();

            runTraining();

            if(this.cleanTempDir) {
                // Clear a previous run
                cleanDirectory(this.tempDirectory);
            }

            // Get spotiflow prediction from the validation
            runSpotiflowOnValidationImages();

            // get QC metrics based on validation images (GT vs prediction)
            this.qcResults = runSpotiflowQC();

            return this.trainingOutputDir;
        } catch (IOException | InterruptedException e) {
            logger.error("Error while running spotiflow training: {}", e.getMessage(), e);
            return null;
        }
    }

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

        spotiflowArguments.add(this.trainingInputDir.getParentFile().getAbsolutePath());
        spotiflowArguments.add("--outdir");
        spotiflowArguments.add(this.modelDir.getAbsolutePath());

        spotiflowArguments.add("--num-epochs");
        spotiflowArguments.add(String.valueOf(this.nEpochs));

        if(this.doNotApplyDataAugmentation){
            spotiflowArguments.add("--augment");
            spotiflowArguments.add("False");
        }

        if(this.modelToFineTune != null && !this.modelToFineTune.isEmpty()){
            spotiflowArguments.add("--finetune-from");
            spotiflowArguments.add(this.modelToFineTune);
        }

        if(process3d){
            spotiflowArguments.add("--is-3d");
            spotiflowArguments.add("True");
        }

        if(lr > 0){
            spotiflowArguments.add("--lr");
            spotiflowArguments.add(String.valueOf(this.lr));
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
    }

    /**
     * Run spotiflow on test images (i.e. validation images) after the training of a new model.
     * This step is necessary to be able to get some QC metrics.
     */
    private void runSpotiflowOnValidationImages(){
        // copy validation images
        this.imageDirectory = this.testDir;
        try {
            // only copy files if one or the other training folders has been cleaned
            if(this.cleanTrainingDir || this.cleanTempDir) {
                logger.info("Copying validation images into prediction folder");
                FileUtils.copyDirectory(this.validationInputDir, this.imageDirectory);
            }
        } catch (IOException e) {
            logger.error("Failed to copy validation images in the temp image directory", e);
        }

        // run spotiflow with the new created model
        try {
            logger.info("Running Spotiflow on Validation image");
            runSpotiflow();
        } catch (IOException | InterruptedException e) {
            logger.error("Failed to Run Spotiflow on validation images", e);
        }
    }

    /**
     * Runs the python script "run-spotiflow-qc.py", which should be in the QuPath Extensions folder
     *
     * @return the results table with the QC metrics or null
     * @throws IOException          if the python script is not found
     * @throws InterruptedException if the running the QC fails for some reason
     */
    private ResultsTable runSpotiflowQC() throws IOException, InterruptedException {
        File qcFolder = new File(this.trainingOutputDir, "QC");
        qcFolder.mkdirs();

        logger.info("Running QC script on validation images");

        // Let's check if the QC notebook is available in the 'extensions' folder
        String spotiflowVersion = SpotiflowExtension.getExtensionVersion();
        List<File> extensionDirList = QuPathGUI.getExtensionCatalogManager()
                .getCatalogManagedInstalledJars()
                .parallelStream()
                .filter(e->e.toString().contains("qupath-extension-spotiflow-"+spotiflowVersion))
                .map(Path::getParent)
                .map(Path::toString)
                .map(File::new)
                .collect(Collectors.toList());

        if(extensionDirList.isEmpty()){
            logger.warn("Spotiflow extension not installed ; cannot find QC script");
            return null;
        }

        String scriptName = "run-spotiflow-qc.py";
        File qcPythonFile = new File(extensionDirList.getFirst(), scriptName);
        if (!qcPythonFile.exists()) {
            logger.warn("File {} was not found in {}.\nPlease download it from {}", qcPythonFile.getName(),
                    extensionDirList.getFirst().getAbsolutePath(),
                    new SpotiflowExtension().getRepository().getUrlString());
            return null;
        }

        // Start the Virtual Environment Runner
        VirtualEnvironmentRunner qcRunner = getVirtualEnvironmentRunner("");

        // This is the list of commands after the 'python' call
        List<String> spotiflowArguments = new ArrayList<>();

        spotiflowArguments.add(qcPythonFile.getAbsolutePath());
        spotiflowArguments.add("--ground-truth");
        spotiflowArguments.add(this.validationInputDir.getAbsolutePath());

        spotiflowArguments.add("--predictions");
        spotiflowArguments.add(this.imageDirectory.getAbsolutePath());

        File qcFile = new File(qcFolder.getAbsolutePath() + File.separator + "QC_metrics.csv");
        spotiflowArguments.add("--outfile");
        spotiflowArguments.add(qcFile.getAbsolutePath());

        qcRunner.setArguments(spotiflowArguments);
        qcRunner.runCommand(true);

        return ResultsTable.open(qcFile.getAbsolutePath());
    }

    /**
     * Get the results table associated with the Quality Control run
     *
     * @return the results table with the QC metrics
     */
    public ResultsTable getQCResults() {
        return this.qcResults;
    }

    /**
     * Goes through the current project and saves the images and point coordinates
     * to the training and validation directories
     */
    public void saveTrainingImages() {

        // Create the required directories if they don't exist
        File trainDirectory = this.trainingInputDir;
        trainDirectory.mkdirs();

        File valDirectory = this.validationInputDir;
        valDirectory.mkdirs();

        Project<BufferedImage> project = QPEx.getQuPath().getProject();
        project.getImageList().forEach(e -> {

            ImageData<BufferedImage> imageData;
            try {
                imageData = e.readImageData();
                int nCurrentZ = imageData.getServer().nZSlices();
                if(process3d) {
                    zCurrentEnd = zEnd;
                    if (zCurrentEnd < 0) {
                        zCurrentEnd = nCurrentZ - 1;
                    }
                    if (zCurrentEnd < zStart) {
                        logger.warn("Z Positions are not valid: start: {} > end: {}. Take all slices instead.", zStart, zCurrentEnd);
                        zStart = 0;
                        zCurrentEnd = nCurrentZ - 1;
                    }
                }

                String imageName = GeneralTools.stripExtension(imageData.getServer().getMetadata().getName());

                Collection<PathObject> allAnnotations = imageData.getHierarchy().getAnnotationObjects();
                // Get Squares for Training, Validation and Testing
                List<PathObject> trainingAnnotations = allAnnotations.stream()
                        .filter(a -> a.getROI() instanceof RectangleROI && a.getPathClass() != null &&
                                a.getPathClass().getName().equalsIgnoreCase("training"))
                        .collect(Collectors.toList());
                List<PathObject> validationAnnotations = allAnnotations.stream()
                        .filter(a -> a.getROI() instanceof RectangleROI && a.getPathClass() != null &&
                                a.getPathClass().getName().equalsIgnoreCase("validation"))
                        .collect(Collectors.toList());

                // TODO add test annotations too
                //List<PathObject> testingAnnotations = allAnnotations.stream().filter(a -> a.getPathClass() == PathClass.getInstance("Test")).collect(Collectors.toList());

                logger.info("Found {} Training rectangle objects and {} Validation rectangle objects in image {}",
                        trainingAnnotations.size(), validationAnnotations.size(), imageName);

                if (!trainingAnnotations.isEmpty() || !validationAnnotations.isEmpty()) {
                    // force resolving the hierarchy to get access to child objects
                    imageData.getHierarchy().resolveHierarchy();

                    // saving images & points
                    saveImageAndPointCoordinates(trainingAnnotations, imageName, imageData, trainDirectory);
                    saveImageAndPointCoordinates(validationAnnotations, imageName, imageData, valDirectory);
                }
            } catch (Exception ex) {
                logger.error(ex.getMessage());
            }
        });
    }

    /**
     * Saves the images of the regions and the csv file containing points coordinates
     *
     * @param annotations    the annotations in which to create RegionRequests to save
     * @param imageName      the desired name of the images
     * @param imageData      the current imageData
     * @param saveDirectory  the location where to save the pair of images
     */
    private void saveImageAndPointCoordinates(List<PathObject> annotations, String imageName,
                                              ImageData<BufferedImage> imageData, File saveDirectory) {
        if (annotations.isEmpty()) {
            return;
        }

        // training only possible on ONE channel
        String channel = this.channels.entrySet().iterator().next().getKey();

        String fileExtension = this.TIFF_FILE_EXTENSION;
        this.isOmeZarr = false; // ome-zarr not currently supported by spotiflow for training

        annotations.forEach(a -> {
            List<PathObject> gtPointsList;

            // filter annotations according to the current plan or to the full stack
            if(process3d){
                gtPointsList = imageData.getHierarchy().getAnnotationObjects().stream()
                        .filter(e->e.getROI() instanceof PointsROI
                                && a.getROI().contains(e.getROI().getCentroidX(), e.getROI().getCentroidY()))
                        .collect(Collectors.toList());
            }else{
                gtPointsList = a.getChildObjects().stream()
                        .filter(e->e.getROI() instanceof PointsROI)
                        .collect(Collectors.toList());
            }

            if(!this.pointClasses.isEmpty()){
                gtPointsList = gtPointsList.stream()
                        .filter(e->e.getPathClass() != null && pointClasses.contains(e.getPathClass().getName().toLowerCase()))
                        .collect(Collectors.toList());
            }

            // create the csv file with point coordinates (in 2D/3D)
            if(!gtPointsList.isEmpty() || this.includeNegatives) {
                List<String> pointCoordinatesList = new ArrayList<>();
                if(process3d) {
                    pointCoordinatesList.add("z,y,x");
                    for (PathObject point : gtPointsList) {
                        PointsROI pointRoi = (PointsROI)(point.getROI());
                        pointRoi.getAllPoints().forEach(e->pointCoordinatesList.add(String.format("%d,%f,%f", pointRoi.getZ() - zStart, e.getY(), e.getX())));
                    }
                }else{
                    pointCoordinatesList.add("y,x");
                    for (PathObject point : gtPointsList) {
                        PointsROI pointRoi = (PointsROI)(point.getROI());
                        pointRoi.getAllPoints().forEach(e->pointCoordinatesList.add(String.format("%f,%f",e.getY(), e.getX())));
                    }
                }

                // save the image
                String name = saveImage(imageData, saveDirectory, a, channel, fileExtension, imageName);

                // write the file
                File pointFile = new File(saveDirectory, name + ".csv");
                try (BufferedWriter buffer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(pointFile), StandardCharsets.UTF_8))) {
                    buffer.write(String.join("\n", pointCoordinatesList));
                }catch(Exception e){
                    logger.error(e.getMessage());
                    logger.error("Error when saving point coordinates");
                }

                logger.info("Saved image & point coordinates: \n\t{}\n\t{}", name + fileExtension, name + ".csv");
            }
        });
    }

    /**
     * Get the index of the channels from their name and fill a map<ChannelName, ChannelIndex>
     *
     * @param imageData  the current imageData
     * @param channels   the channels name
     */
    private void setupChannels(ImageData<BufferedImage> imageData, String[] channels) {
        this.channels = new HashMap<>();
        ImageServer<BufferedImage> currentServer = imageData.getServer();
        for(String channel : channels){
            for(int i = 0; i < currentServer.nChannels(); i++) {
                String chName = currentServer.getChannel(i).getName();
                if (channel.equals(chName)) {
                    this.channels.put(chName, i);
                    break;
                }
            }
        }
    }

    /**
     * Get the name of the channels from their index and fill a map&lt;ChannelName, ChannelIndex&gt;
     *
     * @param imageData  the current imageData
     * @param channels   the channels indices
     */
    public void setupChannels(ImageData<BufferedImage> imageData, Integer[] channels) {
        this.channels = new HashMap<>();
        for(int channel : channels){
            this.channels.put(imageData.getServer().getChannel(channel).getName(), channel);
        }
    }
}