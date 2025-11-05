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
//        .setModelDir(new File("path/to/my/model"))           // OPTIONAL : path to your own trained model
//        .setPretrainedModelName("smfish_3d")                 // OPTIONAL : Default is 'general'
//        .setMinDistance(2)                                   // OPTIONAL : Positive integer value
//        .setProbabilityThreshold(0.2)                        // OPTIONAL : Positive value
//        .disableGPU()                                        // OPTIONAL : Force using CPU ; default is automatic (let spotiflow decide)
//        .process3d()                                         // OPTIONAL : process the entire zstack
//        .zPositions(0,5)                                     // OPTIONAL : ONLY works wih process3d(). Select a sub-stack (start and end inclusive)
//        .doSubpixel(true)                                    // OPTIONAL : true to get subpixel resolution ; false to not. Default: let spotiflow choose
//        .setClass("ClassName")                               // OPTIONAL : set the same class for all detections. Default: not assign any classes
        .setClassChannelName()                               // OPTIONAL : create a new class for each channel and assign detection to it. Default: not assign any classes
//        .nThreads(12)                                        // OPTIONAL : How much you want to paralellize processing. Default 12
//        .saveBuilder("MyFancyName")                          // OPTIONAL : To save builder parameters as JSON file
//        .saveTempImagesAsOmeZarr()                           // OPTIONAL : ONLY AVAILABLE FOR SPOTIFLOW >= 0.5.8. Save temp images as ome-zarr instead of ome.tiff
//        .clearAllChildObjects()                              // OPTIONAL : Clear all previous detections, whatever their class
//        .createAnnotations()                                 // OPTIONAL : Create annotations instead of detections. WARNING: this can slow up a lot QuPath. Only to use to pre-annotated small patches for later training.
        .clearChildObjectsBelongingToCurrentChannels()       // OPTIONAL : Clear all previous detections which belong to the current selected channels (i.e. with their class set with the name of the channel)
        .channels("channel 1", "channel 2")        // REQUIRED : list of channel name(s) to process. At least one channel is required
        .cleanTempDir()                                      // OPTIONAL : Clean all files from the tempDirectory
//        .addParameter("key","value")                         // OPTIONAL : Add more parameter, base on the available ones
        .build()

// Run detection for the selected objects
def imageData = getCurrentImageData()
def pathObjects = getSelectedObjects() // To process only selected annotations, useful while testing
// def pathObjects = getAnnotationObjects() // To process all annotations. For working in batch mode
if (pathObjects.isEmpty()) {
    Dialogs.showErrorMessage( "Spotiflow", "Please select a parent object!" )
    return
}

// print the available arguments for prediction
//spotiflow.helpPredict()

spotiflow.detectObjects( imageData, getProjectEntry().getImageName(), pathObjects )

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