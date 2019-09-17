import org.jetbrains.kotlin.gradle.tasks.KotlinCompile


val jacksonVersion = "2.9.9"

group = "com.dc2f"
if (version == "unspecified") {
    version = "0.1.3-SNAPSHOT"
}

plugins {
    // Apply the Kotlin JVM plugin to add support for Kotlin on the JVM
    id("org.jetbrains.kotlin.jvm").version("1.3.10")
    `maven-publish`
    signing
}

val secretConfig = file("_tools/secrets/build_secrets.gradle.kts")
if (secretConfig.exists()) {
    apply { from("_tools/secrets/build_secrets.gradle.kts") }
} else {
    println("Warning: Secrets do not exist, maven publish will not be possible.")
}

tasks.register<Jar>("sourcesJar") {
    from(sourceSets.main.get().allSource)
    archiveClassifier.set("sources")
}
tasks.register<Jar>("javadocJar") {
    from(tasks.javadoc)
    archiveClassifier.set("javadoc")
}

publishing {
    publications {
        create<MavenPublication>("mavenCentralJava") {
            from(components["java"])
            artifact(tasks["sourcesJar"])
//            artifact(sourcesJar.get())
            artifact(tasks["javadocJar"])


            pom {
                name.set("DC2F")
                description.set("Type safe static website generator")
                url.set("https://github.com/dc2f/dc2f.kt")
//                properties.set(mapOf(
//                    "myProp" to "value",
//                    "prop.with.dots" to "anotherValue"
//                ))
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("hpoul")
                        name.set("Herbert Poul")
                        email.set("herbert@poul.at")
                    }
                }
                scm {
                    connection.set("scm:git:http://github.com/dc2f/dc2f.kt.git")
                    developerConnection.set("scm:git:ssh://github.com/dc2f/dc2f.kt.git")
                    url.set("https://github.com/dc2f/dc2f.kt")
                }
            }

        }
    }
    repositories {
        maven {
            val releasesRepoUrl = "https://oss.sonatype.org/service/local/staging/deploy/maven2/"
            val snapshotsRepoUrl = "https://oss.sonatype.org/content/repositories/snapshots/"
            url = uri(if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl)
            println("using $url")
            credentials {
                username = project.properties["ossrhUsername"] as? String
                password = project.properties["ossrhPassword"] as? String
            }

        }
//        maven {
//            name = "github"
//            url = uri("https://maven.pkg.github.com/dc2f")
//            credentials {
//                username = project.properties["dc2f.github.username"] as? String
//                password = project.properties["dc2f.github.password"] as? String
//            }
//        }
    }
}

signing {
    sign(publishing.publications["mavenCentralJava"])
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
    kotlinOptions.freeCompilerArgs = listOf("-Xjvm-default=enable")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}


repositories {
    // Use jcenter for resolving your dependencies.
    // You can declare any Maven/Ivy/file repository here.
    jcenter()
}

dependencies {
    // Use the Kotlin JDK 8 standard library
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // logging
    implementation("io.github.microutils:kotlin-logging:1.4.9")
    implementation("org.slf4j:jul-to-slf4j:1.7.25")
    implementation("ch.qos.logback:logback-classic:1.2.1")

    // annoying image stuff
    implementation("net.coobird:thumbnailator:0.4.8")

    // yaml deserialize
    compile("com.fasterxml.jackson.core:jackson-annotations:$jacksonVersion")
    compile("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:$jacksonVersion")
    implementation("com.fasterxml.jackson.module:jackson-module-mrbean:$jacksonVersion")

    implementation("cglib:cglib:3.2.12")

//    compile("org.hibernate.validator:hibernate-validator:6.0.14.Final")
//    implementation("org.glassfish:javax.el:3.0.1-b09")

    // utils
    implementation("org.apache.commons:commons-lang3:3.8.1")
    implementation("org.reflections:reflections:0.9.11")
    compile("com.google.guava:guava:27.0.1-jre")

    implementation("io.ktor:ktor-http-jvm:1.1.2") // mainly for UrlBuilder
    implementation("io.ktor:ktor-http:1.1.2") // mainly for UrlBuilder

    // google sitemap generator
    compile("com.github.dfabulich:sitemapgen4j:1.1.2")

    // git support for geting last modified date.
    implementation("org.eclipse.jgit:org.eclipse.jgit:5.2.1.201812262042-r")

    // image io for reading images - jpeg support, bmp: ico support
    compile("com.twelvemonkeys.imageio:imageio-jpeg:3.4.1")
    compile("com.twelvemonkeys.imageio:imageio-bmp:3.4.1")
    compile("org.apache.xmlgraphics:batik-codec:1.10") // required for SVG-inline png support.
    compile("org.apache.xmlgraphics:batik-transcoder:1.10")
    compile("com.twelvemonkeys.imageio:imageio-batik:3.4.1") // SVG support
    implementation("com.ibm.icu:icu4j:63.1")


    // content parsers
    implementation("com.vladsch.flexmark:flexmark-all:0.40.16")
    implementation("org.jodd:jodd-bean:5.0.8") // for resolving paths
    implementation("com.github.spullara.mustache.java:compiler:0.9.6")
    implementation("io.pebbletemplates:pebble:3.0.8")
    implementation("org.springframework:spring-expression:5.1.5.RELEASE") // right now only used to parse "arguments" from markdown

    // render/"templating"
    compile("org.jetbrains.kotlinx:kotlinx-html-jvm:0.6.12")
    // preprocessors
    implementation("io.bit3:jsass:5.7.3")

    // caching
    implementation("org.ehcache:ehcache:3.6.1")

    // Use the Kotlin test library
    testImplementation("org.jetbrains.kotlin:kotlin-test")

    // Use the Kotlin JUnit integration
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.1.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.1.0")

    testImplementation("io.mockk:mockk:1.9.1")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.13")
}
