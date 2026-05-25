plugins {
    java
}

group = "com.serverdashboard.backup"
version = "1.2.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly(files("../ServerDashboard/build/libs/ServerDashboard-1.8.2.jar"))
    implementation("com.github.mwiede:jsch:0.2.21")
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }
    jar {
        archiveBaseName.set("ServerDashboard-Backup")
        archiveVersion.set(project.version.toString())
        archiveClassifier.set("")
        // Bundle runtime deps (JSch) directly into the JAR
        from(configurations.runtimeClasspath.get().map {
            if (it.isDirectory) it else zipTree(it)
        }) {
            exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/MANIFEST.MF")
        }
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
}
