/**
 * Spotiflow Detection Template script
 * @author Rémy Dornier
 *
 * This script is a template to detect objects using a Spotiflow model within QuPath.
 * After defining the builder, it will:
 * 1. Find all selected annotations in the current open ImageEntry
 * 2. Export the selected annotations to a temp folder that can be specified with tempDirectory()
 * 3. Run the spotiflow detction using the defined/default model name or path
 * 4. Create the desired objects (i.e. points) with the selected statistics (i.e. spotiflow outputs)
 *
 * NOTE: that this template does not contain all options, but should help get you started
 * See all options by calling spotiflow.helpPredict()
 *
 * NOTE 2: You should change pathObjects get all annotations if you want to run for the project. By default this script
 * will only run on the selected annotations.
 */

// If you have trained a custom model, specify the model directory as a File in setModelDir()
// If you want to use any other pre-trained models, specify its name in setPretrainedModelName()
// -> List of all pre-trained models : https://weigertlab.github.io/spotiflow/pretrained.html

Date start = new Date()

def spotiflow = Spotiflow.builder()
//        .tempDirectory(new File("path/to/tmp/folder"))       // OPTIONAL : default is in 'qpProject/spotiflow-temp' folder
//        .setTrainingOutputDir(new File("path/to/output"))    // OPTIONAL : default is in 'qpProject/models' folder
//        .disableGPU()                                        // OPTIONAL : Force using CPU ; default is automatic (let spotiflow decide)
//        .process3d()                                         // OPTIONAL : if you have a zstack and you want to create a 3D model
//        .nThreads(12)                                        // OPTIONAL : How much you want to parallelize processing. Default 12
//        .saveBuilder("MyFancyName")                          // OPTIONAL : To save builder parameters as JSON file
        .channels("Channel 1")                     // REQUIRED : The channel to process. Only one channel is supported for training
//        .doNotApplyDataAugmentation()                        // OPTIONAL : Do not Apply data augmentation during training
//        .nEpochs(20)                                       // OPTIONAL : default 200
//        .setModelToFineTune("general")                       // OPTIONAL : Name of the pre-trained model to fine-tune
        .cleanTempDir()                                      // OPTIONAL : Clean all files from the tempDirectory
//        .addParameter("key","value")                         // OPTIONAL : Add more parameter, base on the available ones
        .build()


// print the available arguments for training
//spotiflow.helpTrain()

spotiflow.train()

// You could do some post-processing here, e.g. to remove objects that are too small, but it is usually better to
// do this in a separate script so you can see the results before deleting anything.

Date stop = new Date()
long milliseconds = stop.getTime() - start.getTime()
int seconds = (int) (milliseconds / 1000) % 60 ;
int minutes = (int) ((milliseconds / (1000*60)) % 60);
int hours   = (int) ((milliseconds / (1000*60*60)) % 24);
println "Processing done in " + hours + " hour(s) " + minutes + " minute(s) " + seconds + " second(s)"
println 'Spotiflow detection script done'

import qupath.ext.biop.spotiflow.Spotiflow