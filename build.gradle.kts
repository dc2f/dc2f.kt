import org.jetbrains.kotlin.gradle.tasks.KotlinCompile


val jacksonVersion = "2.9.9"

group = "com.dc2f"
// One number across dc2f.kt, dc2f-common and dc2f-edit-api: the two siblings
// depend on this module at their own `$version`, so a release only resolves if
// all three carry the same one. Central has had 0.2.1 since 2019 while the three
// repositories declared 0.1.3-SNAPSHOT, 0.0.2 and 0.0.2 locally, so 0.3.0 is the
// first number unambiguously ahead of what is published.
// A release overrides this: `./gradlew publish -Pversion=0.3.0`.
if (version == "unspecified") {
    version = "0.3.0-SNAPSHOT"
}

plugins {
    // Apply the Kotlin JVM plugin to add support for Kotlin on the JVM
    kotlin("jvm") version "2.4.10"
    // Provides api(), which replaces the compile() configuration that the
    // Kotlin plugin used to register and dropped in 1.9.
    `java-library`
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
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
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
                    connection.set("scm:git:https://github.com/dc2f/dc2f.kt.git")
                    developerConnection.set("scm:git:ssh://github.com/dc2f/dc2f.kt.git")
                    url.set("https://github.com/dc2f/dc2f.kt")
                }
            }

        }
    }
    repositories {
        maven {
            name = "central"
            // OSSRH was decommissioned on 2025-06-30 and both of its hosts now
            // return 404. For a hand-rolled maven-publish setup like this one,
            // Sonatype's replacement is the OSSRH Staging API in front of the
            // Central Portal: the same deploy layout, authenticated with a
            // Central Portal user token instead of the old OSSRH password.
            //
            // A release uploaded here is staged and does NOT appear in the
            // Portal until _tools/central-publish.sh posts it -- from the same
            // IP that uploaded, which is why that script runs in the same job.
            val releasesRepoUrl = "https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/"
            val snapshotsRepoUrl = "https://central.sonatype.com/repository/maven-snapshots/"
            url = uri(if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl)
            println("using $url")
            credentials {
                // The token generated at central.sonatype.com, by property or by
                // environment. The ossrh* names stay as a fallback so the
                // encrypted build_secrets.gradle.kts keeps working once its
                // values are replaced with a Portal token -- the old OSSRH
                // credentials themselves are rejected with 401.
                username = (project.findProperty("centralUsername")
                    ?: System.getenv("CENTRAL_TOKEN_USERNAME")
                    ?: project.findProperty("ossrhUsername")) as? String
                password = (project.findProperty("centralPassword")
                    ?: System.getenv("CENTRAL_TOKEN_PASSWORD")
                    ?: project.findProperty("ossrhPassword")) as? String
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
    // The armoured private key arrives in SIGNING_KEY: from a repository secret
    // in CI, and locally from `eval "$(_tools/secrets-env.sh)"`, which reads it
    // out of sops. This in-memory path is now the only signing path. The
    // signing.* properties that used to drive gpg named the 2019 key and the
    // committed secring.gpg, both superseded by 464802BC…83D477BC.
    //
    // isNullOrBlank(), not != null: an empty SIGNING_KEY is not a key, but it
    // used to pass the guard and then fail far away as "no configured
    // signatory".
    val signingKey = System.getenv("SIGNING_KEY")
    if (!signingKey.isNullOrBlank()) {
        val signingPassword = System.getenv("SIGNING_PASSWORD")
        // Checked here because the alternative is unreadable. Passing null
        // throws a NullPointerException out of useInMemoryPgpKeys(), and
        // passing "" gets you "PGPException: checksum mismatch in checksum of
        // 20 bytes" from BouncyCastle when it fails to unlock the key -- a
        // message that names neither the passphrase nor this file.
        require(!signingPassword.isNullOrBlank()) {
            "SIGNING_KEY is set but SIGNING_PASSWORD is not, and the key is " +
                "passphrase-protected. Set both, or run: " +
                "eval \"\$(_tools/secrets-env.sh)\""
        }
        useInMemoryPgpKeys(signingKey, signingPassword)
    }
    sign(publishing.publications["mavenCentralJava"])
}

tasks.withType<KotlinCompile> {
    // sourceCompatibility dropped: KotlinCompile stopped extending AbstractCompile
    // in Kotlin 1.9, and it was a no-op here regardless (no Java sources).
    // kotlinOptions was removed in Kotlin 2.2; compilerOptions replaces it,
    // and -Xjvm-default became -jvm-default. "enable" is the equivalent of the
    // old "all-compatibility": it still emits the DefaultImpls bridges that
    // already-compiled consumers of this published library link against.
    compilerOptions {
        freeCompilerArgs.add("-jvm-default=enable")
    }
}

// Pins both the Java and Kotlin targets together. Setting only
// compilerOptions.jvmTarget lets compileJava follow whatever JDK is running and
// Gradle then rejects the mismatch. Gradle 9 requires JDK 17 to run anyway, and
// dc2f already depends on a Java 9 artifact (webp-imageio), so the old
// jvmTarget of 1.8 was fiction.
kotlin {
    jvmToolchain(17)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}


repositories {
    // Use mavenCentral for resolving your dependencies.
    mavenCentral()
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
    implementation("net.coobird:thumbnailator:0.4.14")

    // yaml deserialize
    api("com.fasterxml.jackson.core:jackson-annotations:$jacksonVersion")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:$jacksonVersion")
    implementation("com.fasterxml.jackson.module:jackson-module-mrbean:$jacksonVersion")

//    implementation("cglib:cglib:3.2.12")
    implementation("net.bytebuddy:byte-buddy:1.11.6")

//    compile("org.hibernate.validator:hibernate-validator:6.0.14.Final")
//    implementation("org.glassfish:javax.el:3.0.1-b09")

    // utils
    implementation("org.apache.commons:commons-lang3:3.8.1")
    implementation("org.reflections:reflections:0.9.11")
    api("com.google.guava:guava:27.0.1-jre")

    implementation("io.ktor:ktor-http-jvm:1.1.2") // mainly for UrlBuilder
    implementation("io.ktor:ktor-http:1.1.2") // mainly for UrlBuilder

    // google sitemap generator
    api("com.github.dfabulich:sitemapgen4j:1.1.2")

    // git support for geting last modified date.
    implementation("org.eclipse.jgit:org.eclipse.jgit:5.2.1.201812262042-r")

    // image io for reading images - jpeg support, bmp: ico support
    api("com.twelvemonkeys.imageio:imageio-jpeg:3.4.1")
    api("com.twelvemonkeys.imageio:imageio-bmp:3.4.1")
    api("org.apache.xmlgraphics:batik-codec:1.10") // required for SVG-inline png support.
    api("org.apache.xmlgraphics:batik-transcoder:1.10")
    api("com.twelvemonkeys.imageio:imageio-batik:3.4.1") // SVG support
    // WEBP support. Fork of the same luciad codebase org.sejda.imageio used,
    // but with aarch64 natives -- sejda 0.1.1 shipped mac/64 (x86_64) only,
    // which is why it had to go. TwelveMonkeys' imageio-webp is decode-only
    // and cannot replace this. 0.8.0 is the newest release whose kotlin-stdlib
    // (1.9.22) still matches our Kotlin.
    api("com.github.usefulness:webp-imageio:0.8.0")
    implementation("com.ibm.icu:icu4j:63.1")


    // content parsers
    implementation("com.vladsch.flexmark:flexmark-all:0.61.14")
//    implementation("org.jodd:jodd-bean:5.1.4") // for resolving paths
    implementation("commons-beanutils:commons-beanutils:1.9.4")
    implementation("com.github.spullara.mustache.java:compiler:0.9.6")
    implementation("io.pebbletemplates:pebble:3.0.8")
    implementation("org.springframework:spring-expression:5.1.5.RELEASE") // right now only used to parse "arguments" from markdown

    // render/"templating"
    api("org.jetbrains.kotlinx:kotlinx-html-jvm:0.7.3")
    // preprocessors
    // jsass wraps libsass, which was deprecated in 2020 and never learned the
    // sass module system -- it passes `@use` through as an unknown at-rule and
    // compiles a stylesheet with nothing in it. sass-embedded-host speaks the
    // embedded protocol to the real dart-sass binary.
    implementation("de.larsgrefer.sass:sass-embedded-host:4.2.0")

    // caching
    implementation("org.ehcache:ehcache:3.6.1")
    api("org.ehcache:ehcache:3.6.1")

    // Use the Kotlin test library. From Kotlin 1.9 this picks its own framework
    // variant from the Test task, so useJUnitPlatform() above resolves it to
    // kotlin-test-junit5 and kotlin.test.Test aliases the JUnit 5 annotation.
    testImplementation("org.jetbrains.kotlin:kotlin-test")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.1.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.1.0")

    testImplementation("io.mockk:mockk:1.9.3")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.13")
}
