plugins {
    java
}

group = "com.serverdashboard.backup"
version = "1.0.0"

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
    // Reference the main plugin JAR for the DashboardModule API
    compileOnly(files("../ServerDashboard/build/libs/ServerDashboard-1.8.2.jar"))
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
    }
}
