import org.gradle.api.Project
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

fun Project.compileKotlin(fn: KotlinCompile.() -> Unit) = tasks.withType<KotlinCompile>().forEach(fn)
