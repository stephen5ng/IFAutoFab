plugins {
    kotlin("jvm")
    application
}

application {
    mainClass.set("com.ifautofab.terminal.TerminalMainKt")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(kotlin("stdlib"))
}
