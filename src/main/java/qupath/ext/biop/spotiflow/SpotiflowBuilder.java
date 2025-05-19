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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.images.servers.ColorTransforms;
import qupath.lib.scripting.QP;
import qupath.opencv.ops.ImageDataOp;
import qupath.opencv.ops.ImageOps;

import java.io.*;
import java.util.*;

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
    private ColorTransforms.ColorTransform[] channels = new ColorTransforms.ColorTransform[0];

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
        return channels(Arrays.stream(channels)
                .mapToObj(c -> ColorTransforms.createChannelExtractor(c))
                .toArray(ColorTransforms.ColorTransform[]::new));
    }

    /**
     * Specify channels by name. Useful for detecting nuclei for one channel
     * within a multi-channel image, or potentially for trained models that
     * support multi-channel input.
     *
     * @param channels channels names to use
     * @return this builder
     */
    public SpotiflowBuilder channels(String... channels) {
        return channels(Arrays.stream(channels)
                .map(c -> ColorTransforms.createChannelExtractor(c))
                .toArray(ColorTransforms.ColorTransform[]::new));
    }

    /**
     * Define the channels (or color transformers) to apply to the input image.
     * <p>
     * This makes it possible to supply color deconvolved channels, for example.
     *
     * @param channels the channels to use
     * @return this builder
     */
    public SpotiflowBuilder channels(ColorTransforms.ColorTransform... channels) {
        this.channels = channels.clone();
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



    //  SPOTIFLOW OPTIONS
    // ------------------
    /**
     * Generic means of adding a spotiflow parameter
     *
     * @param flagName  the name of the flag, eg. "save_every"
     * @param flagValue the value that is linked to the flag, eg. "20". Can be an empty string or null if it is not needed
     * @return this builder
     */
    public SpotiflowBuilder addParameter(String flagName, String flagValue) {
        this.spotiflowParameters.put(flagName, flagValue);
        return this;

    }

    /**
     * Generic means of adding a spotiflow parameter
     *
     * @param flagName the name of the flag, eg. "save_every"	 * @param flagName the name of the flag, eg. "save_every"
     * @return this builder
     */
    public SpotiflowBuilder addParameter(String flagName) {
        addParameter(flagName, null);
        return this;
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

        // check number of channel to process. Spotiflow only works with one channel at a time
        if(this.channels.length == 0){
            logger.warn("No channels were provided. The first channel will be processed");
            String channelName =  QP.getCurrentImageData().getServer().getChannel(0).getName();
            channels(channelName);
        } else if (this.channels.length > 1) {
            logger.warn("You supplied {} channels, but Spotiflow needs one channel only. Keeping the first one", channels.length);
            this.channels = Arrays.copyOf(this.channels, 1);
        }

        Map<String, ImageDataOp> opMap = new HashMap<>();
        for(ColorTransforms.ColorTransform channel: this.channels){
            opMap.put(channel.getName(), ImageOps.buildImageDataOp(channel));
        }
        spotiflow.opMap = opMap;

        return spotiflow;
    }
}
