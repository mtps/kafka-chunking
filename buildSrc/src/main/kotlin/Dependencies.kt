import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.internal.artifacts.dependencies.DefaultExternalModuleDependency

private fun mvn(group: String, name: String, version: String): ModuleDependency =
        DefaultExternalModuleDependency(group, name, version)

object Deps {
    val kafkaClient = mvn("org.apache.kafka", "kafka-clients", Versions.kafka)
    val logbackClassic = mvn("ch.qos.logback", "logback-classic", Versions.logback)
}

// Use "" for the version so the main project kotlin-dsl version is used.
object KotlinDeps {
    val gradlePlugin = mvn("org.jetbrains.kotlin", "kotlin-gradle-plugin", "")
    val stdlibJdk8 = mvn("org.jetbrains.kotlin", "kotlin-stdlib-jdk8", "")
    val stdlibCommon = mvn("org.jetbrains.kotlin", "kotlin-stdlib-common", "")
}
