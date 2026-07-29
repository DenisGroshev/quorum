plugins {
    id("quorum.java-conventions")
    `java-library`
}

dependencies {
    api(libs.uuid.creator)
    compileOnly(libs.jakarta.persistence.api)
}
