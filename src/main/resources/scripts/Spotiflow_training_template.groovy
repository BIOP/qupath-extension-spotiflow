/**
 * Spotiflow Training Template script
 * @author Rémy Dornier
 *
 * This script is a template to train a new Spotiflow model within QuPath or to fine-tune a pre-trained model.
 * 1. Draw RECTANGLES and assign them a class 'training' or 'validation' (case insensitive).
 * 2. Inside those rectangle, annotate all points manually (you can assign them a class).
 * 3. Save your job !
 *
 * After defining the builder below, it will:
 * 1. Collect all rectangle annotations in the whole project (not only on the current open image)
 *    Only rectangle annotations will be used : other shapes are discarded.
 * 2. Save image patch and point coordinates in the temp folder that can be specified with tempDirectory()
 * 3. Run the spotiflow training with the defined parameters
 * 4. Saves the new model under the trainingOutpuDir
 *
 * NOTE: this template does not contain all options, but should help get you started.
 * See all options by calling spotiflow.helpTrain().
 *
 * NOTE: List of all pre-trained models : https://weigertlab.github.io/spotiflow/pretrained.html
 */

Date start = new Date()

def spotiflow = Spotiflow.builder()
//        .tempDirectory(new File("path/to/tmp/folder"))       // OPTIONAL : default is in 'qpProject/spotiflow-temp' folder
//        .setTrainingOutputDir(new File("path/to/output"))    // OPTIONAL : default is in 'qpProject/models' folder
//        .disableGPU()                                        // OPTIONAL : Force using CPU ; default is automatic (let spotiflow decide)
//        .process3d()                                         // OPTIONAL : if you have a zstack and you want to create a 3D model
//        .zPositions(0,5)                                     // OPTIONAL : ONLY works wih process3d(). Select a sub-stack (start and end inclusive)
//        .nThreads(12)                                        // OPTIONAL : How much you want to parallelize processing. Default 12
//        .saveBuilder("MyFancyName")                          // OPTIONAL : To save builder parameters as JSON file
        .channels("Channel 1")                     // REQUIRED : The channel to process. Only ONE channel is supported for training
//        .doNotApplyDataAugmentation()                        // OPTIONAL : Do not Apply data augmentation during training
//        .nEpochs(20)                                       // OPTIONAL : default 200
//        .setModelToFineTune("general")                       // OPTIONAL : Name of the pre-trained model to fine-tune
//        .setLearningRate(0.001)                              // OPTIONAL : Set learning rate for the model. Default 0.0003.
//        .includeNegatives()                                  // OPTIONAL : Export rectangles even if they contain zero spots (CSV will then be empty). By default, empty rectangles are not exported.
//        .setPointClass("class1")                             // OPTIONAL : Set the class(es) of points to filter within the parent annotation. By default, all points are selected.
        .cleanTempDir()                                      // OPTIONAL : Clean all files from the tempDirectory
//        .addParameter("key","value")                         // OPTIONAL : Add more parameter, base on the available ones
        .build()


// print the available arguments for training
//spotiflow.helpTrain()

// do the training
def resultModel = spotiflow.train()

// Pick up results to see how the training was performed
if(resultModel != null) {
    println "Model Saved under: "
    println resultModel.getAbsolutePath().toString().replace('\\', '/') // To make it easier to copy paste in windows
}else{
    println("Spotiflow training has failed ; no model was created")
}

// You can get a ResultsTable of the training.
def results = spotiflow.getQCResults()
if(results != null) {
    results.show("QC Results")
}else {
    println("The QC script has failed ; no QC results to show")
}

// print timing
Date stop = new Date()
long milliseconds = stop.getTime() - start.getTime()
int seconds = (int) (milliseconds / 1000) % 60 ;
int minutes = (int) ((milliseconds / (1000*60)) % 60);
int hours   = (int) ((milliseconds / (1000*60*60)) % 24);
println "Processing done in " + hours + " hour(s) " + minutes + " minute(s) " + seconds + " second(s)"
println 'Spotiflow detection script done'

import qupath.ext.biop.spotiflow.Spotiflow