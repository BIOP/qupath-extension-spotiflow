/*-
 * Copyright 2020-2022 QuPath developers, University of Edinburgh
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

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.io.GsonTools;
import qupath.lib.scripting.QP;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;

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
public class SpotiflowBuilder {

    private static final Logger logger = LoggerFactory.getLogger(SpotiflowBuilder.class);
    private final transient SpotiflowSetup spotiflowSetup;
    private final LinkedHashMap<String, String> spotiflowParameters = new LinkedHashMap<>();

    // parameters for prediction only
    private File modelDir = null;
    private String pretrainedModelName = null;
    private String doSubpixel = "None";
    private double probabilityThreshold = -1;
    private int minDistance = -1;
    private boolean classChannelName = false;
    private String pathClass = null;
    private transient String builderName;
    private boolean clearChildObjectsBelongingToCurrentChannels = false;
    private boolean clearAllChildObjects = false;

    // parameters for Training only
    private String modelToFineTune = null;
    private int nEpochs = 200;
    private File trainingOutputDir = null;
    private boolean doNotApplyDataAugmentation = false;
    private double lr = -1;

    // parameters for both Training and Prediction
    private int nThreads = 12; // default from qupath ome-zarr writer
    private File tempDirectory = null;
    private String[] channels = null;
    private boolean cleanTempDir = false;
    private boolean disableGPU = false;
    private boolean isOmeZarr = false;
    private boolean process3d = false;
    private transient boolean saveBuilder;


    /**
     * Build a spotiflow model
     */
    protected SpotiflowBuilder() {
        // Need to know setup options in order to guide the user in case of version inconsistency
        this.spotiflowSetup = SpotiflowSetup.getInstance();
    }


    /* *************************
     *  For Prediction ONLY
     * ************************/

    /**
     * Set pre-trained model to use for prediction
     *
     * @param modelName  name of the pre-trained model
     * @return this builder
     */
    public SpotiflowBuilder setPretrainedModelName(String modelName) {
        this.pretrainedModelName = modelName;
        return this;
    }

    /**
     * Set probability threshold for peak detection
     *
     * @param probabilityThreshold
     * @return this builder
     */
    public SpotiflowBuilder setProbabilityThreshold(double probabilityThreshold) {
        this.probabilityThreshold = probabilityThreshold;
        return this;
    }

    /**
     * Set minimum distance between spots for NMS
     *
     * @param minDistance
     * @return this builder
     */
    public SpotiflowBuilder setMinDistance(double minDistance) {
        int minDistanceInt = (int)minDistance;
        if(Math.abs(minDistanceInt - minDistance) > 0){
            logger.warn("The minimum distance you set is not an integer number ({}) ; " +
                    "will be rounded to {}", minDistance, minDistanceInt);
        }
        this.minDistance = minDistanceInt;
        return this;
    }

    /**
     * Set model directory to use for prediction
     *
     * @param modelDir  path to the custom model
     * @return this builder
     */
    public SpotiflowBuilder setModelDir(File modelDir) {
        this.modelDir = modelDir;
        return this;
    }

    /**
     * Remove all child objects (i.e. previous points) from the parent shapes
     *
     * @return this builder
     */
    public SpotiflowBuilder clearAllChildObjects() {
        this.clearAllChildObjects = true;
        return this;
    }

    /**
     * Allows to go subpixel resolution
     *
     * @return this builder
     */
    public SpotiflowBuilder setClass(String pathClass) {
        this.pathClass = pathClass;
        return this;
    }

    /**
     * Remove child objects (i.e. previous points) from the parent shapes which belongs to
     * the current selected channel(s) i.e. which have the same class as the channel name.
     * Should be used together with {@link SpotiflowBuilder#setClassChannelName()} to have the
     * desired effect.
     *
     * @return this builder
     */
    public SpotiflowBuilder clearChildObjectsBelongingToCurrentChannels() {
        this.clearChildObjectsBelongingToCurrentChannels = true;
        return this;
    }

    /**
     * Allows to go subpixel resolution
     *
     * @param doSubpixel  override doSubpixel
     * @return this builder
     */
    public SpotiflowBuilder doSubpixel(boolean doSubpixel) {
        this.doSubpixel = String.valueOf(doSubpixel);
        return this;
    }

    /**
     * Allows to go subpixel resolution
     *
     * @return this builder
     */
    public SpotiflowBuilder setClassChannelName() {
        this.classChannelName = true;
        return this;
    }

    /* *************************
     *  For Training ONLY
     * ************************/

    /**
     * Set output folder to save a new model
     *
     * @param outputDir  path to the training input dir
     * @return this builder
     */
    public SpotiflowBuilder setTrainingOutputDir(File outputDir) {
        this.trainingOutputDir = outputDir;
        return this;
    }

    /**
     * Set the name of the pre-trained model to fine-tune
     *
     * @param pretrainedModelName  name of the pre-trained model
     * @return this builder
     */
    public SpotiflowBuilder setModelToFineTune(String pretrainedModelName) {
        this.modelToFineTune = pretrainedModelName;
        return this;
    }

    /**
     * Force to not apply data augmentation on images during the training
     *
     * @return this builder
     */
    public SpotiflowBuilder doNotApplyDataAugmentation() {
        this.doNotApplyDataAugmentation = true;
        return this;
    }

    /**
     * Set the number of epochs for the training
     *
     * @param nEpochs  number of epochs
     * @return this builder
     */
    public SpotiflowBuilder nEpochs(int nEpochs) {
        this.nEpochs = nEpochs;
        return this;
    }

    /**
     * Set the learning rate of the model for the training
     *
     * @param learningRate
     * @return this builder
     */
    public SpotiflowBuilder setLearningRate(double learningRate) {
        this.lr = learningRate;
        return this;
    }


    /* *************************
     *  For BOTH Prediction and Training
     * ************************/

    /**
     * Specify the number of threads to use for processing.
     * If you encounter problems, setting this to 1 may help to resolve them by preventing
     * multithreading.
     *
     * @param nThreads the number of threads to use
     * @return this builder
     */
    public SpotiflowBuilder nThreads(int nThreads) {
        this.nThreads = nThreads;
        return this;
    }

    /**
     * Forces using CPU instead of GPU
     *
     * @return this builder
     */
    public SpotiflowBuilder disableGPU() {
        this.disableGPU = true;
        return this;
    }

    /**
     * Allows to process all slices
     *
     * @param process3d  override process3d
     * @return this builder
     * @deprecated use {@link SpotiflowBuilder#process3d()} instead
     */
    @Deprecated
    public SpotiflowBuilder process3d(boolean process3d) {
        this.process3d = process3d;
        return this;
    }

    /**
     * Allows to process all slices of 3D stack.
     * Has to be used together with a 3D-model (custom or pre-trained)
     *
     * @return this builder
     */
    public SpotiflowBuilder process3d() {
        this.process3d = true;
        return this;
    }

    /**
     * Set input folder to predict spots
     *
     * @param inputDir  path to prediction input dir
     * @return this builder
     */
    public SpotiflowBuilder tempDirectory(File inputDir) {
        this.tempDirectory = inputDir;
        return this;
    }

    /**
     * Set input folder to predict spots
     *
     * @param savePredictionImages  overwrite variable
     * @return this builder
     * @deprecated use {@link SpotiflowBuilder#cleanTempDir()} instead
     */
    @Deprecated
    public SpotiflowBuilder savePredictionImages(boolean savePredictionImages) {
        this.cleanTempDir = savePredictionImages;
        return this;
    }

    /**
     * clear all files in the tem
     *
     * @return this builder
     */
    public SpotiflowBuilder cleanTempDir() {
        this.cleanTempDir = true;
        return this;
    }

    /**
     * Specify channels. Useful for detecting nuclei for one channel
     * within a multi-channel image, or potentially for trained models that
     * support multi-channel input.
     *
     * @param channels 0-based indices of the channels to use
     * @return this builder
     */
    public SpotiflowBuilder channels(int... channels) {
        this.channels = new String[channels.length];
        for(int i = 0; i < channels.length; i++){
            this.channels[i] = String.valueOf(channels[i]);
        }
        return this;
    }

    /**
     * Specify channels by name. Useful for detecting nuclei for one channel
     * within a multichannel image, or potentially for trained models that
     * support multichannel input.
     *
     * @param channels channels names to use
     * @return this builder
     */
    public SpotiflowBuilder channels(String... channels) {
        this.channels = channels;
        return this;
    }

    /**
     * Save this builder as a JSON file in order to be able to reuse it in place
     *
     * @param name A name to append to the JSON file. Keep it meaningful for your needs
     * @return this builder
     */
    public SpotiflowBuilder saveBuilder(String name) {
        this.saveBuilder = true;
        this.builderName = name;
        return this;
    }

    /**
     * True to save images in the temp folder as OME-Zarr files. Otherwise, saved as OME-TIFF
     * WARNING : OME-Zarr option is only available from spotiflow >= 0.5.8
     *
     * @return this builder
     */
    public SpotiflowBuilder saveTempImagesAsOmeZarr() {
        this.isOmeZarr = true;
        return this;
    }

    /**
     * Generic means of adding a spotiflow parameter
     *
     * @param flagName  the name of the flag, e.g. "save_every"
     * @param flagValue the value that is linked to the flag, e.g. "20". Can be an empty string or null if it is not needed
     * @return this builder
     */
    public SpotiflowBuilder addParameter(String flagName, String flagValue) {
        this.spotiflowParameters.put(flagName, flagValue);
        return this;

    }

    /**
     * Generic means of adding a spotiflow parameter
     *
     * @param flagName the name of the flag, e.g. "save_every"
     * @return this builder
     */
    public SpotiflowBuilder addParameter(String flagName) {
        return addParameter(flagName, null);
    }


    /* *************************
     *  Builder
     * ************************/

    /**
     * Create a {@link Spotiflow}, all ready for detection.
     *
     * @return a new {@link Spotiflow} instance
     */
    public Spotiflow build() {
        Spotiflow spotiflow = new Spotiflow();
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH'h'mm");
        LocalDateTime now = LocalDateTime.now();

        // Pick up info on project location and where the data will be stored for training and inference
        File quPathProjectDir = QP.getProject().getPath().getParent().toFile();

        // Prepare temp directory in case it was not set
        if (this.tempDirectory == null) {
            this.tempDirectory = new File(quPathProjectDir, "spotiflow-temp");
        }
        spotiflow.tempDirectory = this.tempDirectory;

        // Give it the number of threads to use
        spotiflow.nThreads = this.nThreads;

        spotiflow.modelDir = this.modelDir;
        spotiflow.pretrainedModelName = this.pretrainedModelName;

        // Prepare temp directory in case it was not set
        if (this.trainingOutputDir == null) {
            this.trainingOutputDir = new File(quPathProjectDir, "models");
        }
        spotiflow.trainingInputDir = new File(this.tempDirectory, "train");
        spotiflow.validationInputDir = new File(this.tempDirectory, "val");
        if(this.modelToFineTune != null && !this.modelToFineTune.isEmpty()) {
            spotiflow.trainingOutputDir = new File(this.trainingOutputDir, dtf.format(now) + "_"+this.modelToFineTune+"_fineTuned_model");
        }else{
            spotiflow.trainingOutputDir = new File(this.trainingOutputDir, dtf.format(now) + "_custom_model");
        }
        spotiflow.spotiflowSetup = this.spotiflowSetup;
        spotiflow.parameters = this.spotiflowParameters;
        spotiflow.cleanTempDir = this.cleanTempDir;
        spotiflow.disableGPU = this.disableGPU;
        spotiflow.probabilityThreshold = this.probabilityThreshold;
        spotiflow.minDistance = this.minDistance;
        spotiflow.channelsIdx = this.channels;
        spotiflow.process3d = this.process3d;
        spotiflow.doSubpixel = this.doSubpixel;
        spotiflow.pathClass = this.pathClass;
        spotiflow.isOmeZarr = this.isOmeZarr;
        spotiflow.classChannelName = this.classChannelName;
        spotiflow.clearAllChildObjects = this.clearAllChildObjects;
        spotiflow.clearChildObjectsBelongingToCurrentChannels = this.clearChildObjectsBelongingToCurrentChannels;
        spotiflow.modelToFineTune = this.modelToFineTune;
        spotiflow.doNotApplyDataAugmentation = this.doNotApplyDataAugmentation;
        spotiflow.nEpochs = this.nEpochs;
        spotiflow.lr = this.lr;

        // If we would like to save the builder we can do it here thanks to Serialization and lots of magic by Pete
        if (this.saveBuilder) {
            Gson gson = GsonTools.getInstance(true);
            File savePath = new File(QP.PROJECT_BASE_DIR, this.builderName + "_" + dtf.format(now) + ".json");

            try {
                FileWriter fw = new FileWriter(savePath);
                gson.toJson(this, SpotiflowBuilder.class, fw);
                fw.flush();
                fw.close();
                logger.info("Spotiflow Builder serialized and saved to {}", savePath);

            } catch (IOException e) {
                logger.error("Could not save builder to JSON file {}", savePath.getAbsolutePath(), e);
            }
        }

        return spotiflow;
    }
}
