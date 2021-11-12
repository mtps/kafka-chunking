import org.gradle.api.GradleException
import java.io.FileInputStream
import java.util.Properties
import kotlin.reflect.KClass
import kotlin.reflect.KVisibility.PUBLIC
import kotlin.reflect.full.memberProperties

private fun readProps(name: String) = FileInputStream(name).use {
    Properties()
            .apply { load(it) }
            .map { it.key as String to it.value as String }
}

private fun <T : Any> Map<String, String>.validate(clazz: KClass<T>) = apply {
    val props = clazz.memberProperties
            .filter { it.visibility == PUBLIC }
            .map { it.name }
    val intersect = props.intersect(keys)

    val notInProps = props - intersect
    if (notInProps.isNotEmpty()) {
        throw GradleException("Found in `object Props` but not in properties: $notInProps")
    }

    val notInFile = keys - intersect
    if (notInFile.isNotEmpty()) {
        throw GradleException("Found in properties but not in `object Props`: $notInFile")
    }
}

private const val versionsFile = "versions.properties"

private fun <T : Any> T.loadVersions() = try {
    readProps(versionsFile).toMap().validate(this::class)
} catch (e: Throwable) {
    throw GradleException("Failed to load $versionsFile", e)
}

object Versions {
    private val map = loadVersions()

    // Kotlin
    val kotlin by map

    // 3rd Party
    val protobuf by map
    val springboot by map
    val jackson by map
    val kafka by map
    val exposed by map
    val rocksdb by map
    val redisson by map
    val postgres by map
    val c3po by map
    val hikari by map
    val javaxAnnotation by map
    val logback by map

    val corelib by map
}
