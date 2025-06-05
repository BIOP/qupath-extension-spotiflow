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

def spotiflow = Spotiflow.builder()
//        .tempDirectory(new File("path/to/tmp/folder"))       // OPTIONAL : default is in qpProject/spotiflow-temp folder
//        .setModelDir(new File("path/to/my/model"))           // OPTIONAL : path to your own trained model
//        .setPretrainedModelName("smfish_3d")                 // OPTIONAL : Default is 'general'
//        .setMinDistance(2)                                   // OPTIONAL : Positive value
//        .setProbabilityThreshold(0.2)                        // OPTIONAL : Positive value
//        .useGPU(false)                                       // OPTIONAL : false to use CPU ; default is true
//        .addParameter("key","value")                         // OPTIONAL : Add more parameter, base on the available ones
//        .process3d(true)                                     // OPTIONAL : process the entire zstack ; default false
        .channels("SPOT")
        .savePredictionImages(false)
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

println 'Spotiflow detection script done'


import qupath.ext.biop.spotiflow.Spotiflow