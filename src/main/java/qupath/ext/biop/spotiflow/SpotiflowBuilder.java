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
import qupath.lib.scripting.QP;

import java.io.*;
import java.util.*;

/**
 * Cell detection based on the following method:
 * <pre>
 *   Uwe Schmidt, Martin Weigert, Coleman Broaddus, and Gene Myers.
 *     "Cell Detection with Star-convex Polygons."
 *   <i>International Conference on Medical Image Computing and Computer-Assisted Intervention (MICCAI)</i>, Granada, Spain, September 2018.
 * </pre>
 * See the main repo at <a href="https://github.com/mpicbg-csbd/stardist">...</a>
 * <p>
 * Very much inspired by stardist-imagej at <a href="https://github.com/mpicbg-csbd/stardist-imagej">...</a> but re-written from scratch to use OpenCV and
 * adapt the method of converting predictions to contours (very slightly) to be more QuPath-friendly.
 * <p>
 * Models are expected in the same format as required by the Fiji plugin, or converted to a frozen .pb file for use with OpenCV.
 *
 * @author Pete Bankhead (this implementation, but based on the others)
 */
public class SpotiflowBuilder {

    private static final Logger logger = LoggerFactory.getLogger(SpotiflowBuilder.class);

    private final transient SpotiflowSetup spotiflowSetup;
    private final LinkedHashMap<String, String> spotiflowParameters = new LinkedHashMap<>();
    private File modelDir = null;
    private String pretrainedModelName = null;
    private File predictionInputDir = null;
    private File trainingInputDir = null;
    private File trainingOutputDir = null;

    /**
     * Build a spotiflow model
     *
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
    public SpotiflowBuilder setPredictionInputDir(File inputDir) {
        this.predictionInputDir = inputDir;
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
        if (this.predictionInputDir == null) {
            this.predictionInputDir = new File(quPathProjectDir, "spotiflow-temp");
        }
        spotiflow.tempDirectory = this.predictionInputDir;

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

        return spotiflow;
    }
}
