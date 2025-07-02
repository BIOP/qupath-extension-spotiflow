plugins {
    id("java-library")
    id("maven-publish")
    id("qupath.extension-conventions")
    id("qupath.javafx-conventions")
}

repositories {
    // Use this only for local development!
    mavenCentral()
    maven{
        url = uri("https://maven.scijava.org/content/repositories/releases")
    }
    maven{
        url = uri("https://maven.scijava.org/content/repositories/ome-releases")
    }
}

group = "ch.epfl.biop"
version = "0.1.0-rc3-SNAPSHOT"
description = "A QuPath extension to run Spotiflow"

var archiveBaseName = "qupath-extension-spotiflow"
var moduleName ="qupath.extension.spotiflow"

base {
    archivesName = archiveBaseName
    description = description
}

val qupathVersion = rootProject.version.toString()

var bioformatsVersion = libs.versions.bioformats.get()
val versionOverride = project.properties["bioformats-version"]
if (versionOverride is String) {
    println("Using specified Bio-Formats version $versionOverride")
    bioformatsVersion = versionOverride
}
dependencies {
    implementation(project(":qupath-extension-bioformats"))
    implementation(project(":qupath-extension-script-editor"))
    implementation(libs.qupath.fxtras)
    implementation("commons-io:commons-io:2.15.0")

    implementation("ome:formats-gpl:${bioformatsVersion}") {
        exclude(group= "xalan", module= "xalan")
        exclude(group= "io.minio", module= "minio")
        exclude(group= "cisd", module= "jhdf5")
        exclude(group= "commons-codec", module= "commons-codec")
        exclude(group= "commons-logging", module= "commons-logging")
        exclude(group= "edu.ucar", module= "cdm")
        exclude(group= "edu.ucar", module= "cdm-core")
        exclude(group= "com.google.code.findbugs", module= "jsr305")
        exclude(group= "com.google.code.findbugs", module= "annotations")
    }
}

tasks.withType<ProcessResources> {
    from ("${projectDir}/LICENSE") {
        into("META-INF/licenses/")
    }
}

tasks.register<Sync>("copyResources") {
    description = "Copy dependencies into the build directory for use elsewhere"
    group = "QuPath"
    from(configurations.default)
    into("build/libs")
}

/*
 * Ensure Java 21 compatibility
 */
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}


/*
 * Adding manifest information
 */
tasks {
    withType<Jar> {
        manifest {
            attributes["Implementation-Title"] = project.name
            attributes["Automatic-Module-Name"] = "${project.group}.$moduleName"
        }
    }
}

/*
 * Create javadocs for all modules/packages in one place.
 * Use -PstrictJavadoc=true to fail on error with doclint (which is rather strict).
 */
val strictJavadoc = findProperty("strictJavadoc")
if (strictJavadoc == false) {
    tasks.withType<Javadoc> {
        (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
    }
}

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
            url = uri(if (project.version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl)
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