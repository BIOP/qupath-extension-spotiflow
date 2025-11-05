plugins {
    id("maven-publish")
    // QuPath Gradle extension convention plugin
    id("qupath-conventions")
}

repositories {
    maven{
        url = uri("https://maven.scijava.org/content/repositories/ome-releases")
    }
}

qupathExtension {
    name = "qupath-extension-spotiflow"
    group = "ch.epfl.biop"
    version = "0.3.1"
    description = "QuPath extension to use Spotiflow"
    automaticModule = "qupath.ext.biop.spotiflow"
}

dependencies {
    implementation(libs.qupath.ext.bioformats){
        exclude(group= "cisd", module= "jhdf5")
        exclude(group= "edu.ucar", module= "cdm-core")
    }
    implementation("ome:formats-api:${libs.versions.bioformats.get()}")
    implementation(libs.qupath.gui.fx)
    implementation(libs.qupath.ext.script.editor)
    implementation(libs.qupath.fxtras)
    implementation(libs.extensionmanager)
    implementation("commons-io:commons-io:2.15.0")
}

/*
 * Set HTML language and destination folder
 */
tasks.withType<Javadoc> {
    (options as StandardJavadocDocletOptions).addBooleanOption("html5", true)
    setDestinationDir(File(project.rootDir,"docs"))
}

/*
 * Avoid "Entry .gitkeep is a duplicate but no duplicate handling strategy has been set."
 * when using withSourcesJar()
 */
tasks.withType<org.gradle.jvm.tasks.Jar> {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

publishing {
    repositories {
        maven {
            name = "SciJava"
            val releasesRepoUrl = "https://maven.scijava.org/content/repositories/releases"
            val snapshotsRepoUrl = "https://maven.scijava.org/content/repositories/snapshots"
            url = if (project.hasProperty("release")) uri(releasesRepoUrl) else uri(snapshotsRepoUrl)
            credentials {
                username = System.getenv("MAVEN_USER")
                password = System.getenv("MAVEN_PASS")
            }
        }
    }

    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                licenses {
                    license {
                        name = "Apache License v2.0"
                        url = "http://www.apache.org/licenses/LICENSE-2.0"
                    }
                }
            }
        }
    }
}