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
import qupath.lib.images.servers.ImageServer;
import qupath.lib.io.GsonTools;
import qupath.lib.scripting.QP;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

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
    private File modelDir = null;
    private String pretrainedModelName = null;
    private File tempDirectory = null;
    private File trainingInputDir = null;
    private File trainingOutputDir = null;
    private Map<String, Integer> channels = new HashMap<>();
    private boolean savePredictionImages = true;
    private boolean disableGPU = false;
    private boolean process3d = false;
    private String doSubpixel = "None";
    private double probabilityThreshold = -1;
    private double minDistance = -1;
    private boolean classChannelName = false;
    private String pathClass = null;
    private transient boolean saveBuilder;
    private transient String builderName;
    private int nThreads = 12; // default from qupath ome-zarr writer

    /**
     * Build a spotiflow model
     */
    protected SpotiflowBuilder() {
        // Need to know setup options in order to guide the user in case of version inconsistency
        this.spotiflowSetup = SpotiflowSetup.getInstance();
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
     */
    public SpotiflowBuilder savePredictionImages(boolean savePredictionImages) {
        this.savePredictionImages = savePredictionImages;
        return this;
    }

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
        this.minDistance = minDistance;
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
     * Specify channels. Useful for detecting nuclei for one channel
     * within a multi-channel image, or potentially for trained models that
     * support multi-channel input.
     *
     * @param channels 0-based indices of the channels to use
     * @return this builder
     */
    public SpotiflowBuilder channels(int... channels) {
        this.channels = new HashMap<>();
        for(int channel : channels){
            this.channels.put(QP.getCurrentImageData().getServer().getChannel(channel).getName(), channel);
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
        this.channels = new HashMap<>();
        ImageServer<BufferedImage> currentServer = QP.getCurrentImageData().getServer();
        for(String channel : channels){
            for(int i = 0; i < currentServer.nChannels(); i++) {
                String chName = currentServer.getChannel(i).getName();
                if (channel.equals(chName)) {
                    this.channels.put(chName, i);
                    break;
                }
            }
        }
        return this;
    }

    /**
     * Set input folder to train a new model
     *
     * @param inputDir  path to the training input dir
     * @return this builder
     */
    public SpotiflowBuilder setTrainingInputDir(File inputDir) {
        this.trainingInputDir = inputDir;
        return this;
    }

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
     * Forces using CPU instead of GPU
     *
     * @param disableGPU  override disableGPU
     * @return this builder
     */
    public SpotiflowBuilder disableGPU(boolean disableGPU) {
        this.disableGPU = disableGPU;
        return this;
    }

    /**
     * Allows to process all slices
     *
     * @param process3d  override process3d
     * @return this builder
     */
    public SpotiflowBuilder process3d(boolean process3d) {
        this.process3d = process3d;
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
     * Allows to go subpixel resolution
     *
     * @return this builder
     */
    public SpotiflowBuilder setClass(String pathClass) {
        this.pathClass = pathClass;
        return this;
    }

    /**
     * Save this builder as a JSON file in order to be able to reuse it in place
     *
     * @param name // A name to append to the JSON file. Keep it meaningful for your needs
     * @return this builder
     */
    public SpotiflowBuilder saveBuilder(String name) {
        this.saveBuilder = true;
        this.builderName = name;
        return this;
    }


    //  SPOTIFLOW OPTIONS
    // ------------------
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


    /**
     * Create a {@link Spotiflow}, all ready for detection.
     *
     * @return a new {@link Spotiflow} instance
     */
    public Spotiflow build() {
        Spotiflow spotiflow = new Spotiflow();

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
        if (this.trainingInputDir == null) {
            this.trainingInputDir = new File(quPathProjectDir, "spotiflow-temp");
        }
        // Prepare temp directory in case it was not set
        if (this.trainingOutputDir == null) {
            this.trainingOutputDir = new File(quPathProjectDir, "spotiflow-training");
        }
        spotiflow.trainingInputDir = this.trainingInputDir;
        spotiflow.trainingOutputDir = this.trainingOutputDir;
        spotiflow.spotiflowSetup = this.spotiflowSetup;
        spotiflow.parameters = this.spotiflowParameters;
        spotiflow.savePredictionImages = this.savePredictionImages;
        spotiflow.disableGPU = this.disableGPU;
        spotiflow.probabilityThreshold = this.probabilityThreshold;
        spotiflow.minDistance = this.minDistance;
        spotiflow.channels = this.channels;
        spotiflow.process3d = this.process3d;
        spotiflow.doSubpixel = this.doSubpixel;
        spotiflow.pathClass = this.pathClass;
        spotiflow.classChannelName = this.classChannelName;

        // If we would like to save the builder we can do it here thanks to Serialization and lots of magic by Pete
        if (this.saveBuilder) {
            Gson gson = GsonTools.getInstance(true);

            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH'h'mm");
            LocalDateTime now = LocalDateTime.now();
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
