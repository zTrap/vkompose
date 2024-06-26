package com.vk.gradle.plugin.recompose.logger

import com.vk.recompose_logger.logger.BuildConfig
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

class RecomposeLoggerPlugin : KotlinCompilerPluginSupportPlugin {

    override fun apply(target: Project) {
        target.extensions.create(EXTENSION_NAME, RecomposeLoggerExtension::class.java)
        target.applyRuntimeDependency()
    }

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean = true

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        val project = kotlinCompilation.target.project

        return project.provider {
            listOf(
                SubpluginOption("enabled", project.isPluginEnabled().toString()),
                SubpluginOption("logModifierChanges", project.logModifierChanges().toString()),
                SubpluginOption("logFunctionChanges", project.logFunctionChanges().toString()),
            )
        }
    }

    override fun getCompilerPluginId(): String = "com.vk.recompose-logger.compiler-plugin"

    override fun getPluginArtifact(): SubpluginArtifact = SubpluginArtifact(
        groupId = "com.vk.recompose-logger",
        artifactId = "compiler-plugin",
        version = BuildConfig.VERSION
    )

    private fun Project.applyRuntimeDependency() = afterEvaluate {
        if (isPluginEnabled()) {
            dependencies {
                add("implementation", "com.vk.recompose-logger:compiler-runtime:${BuildConfig.VERSION}")
            }
        }
    }

    private fun Project.isPluginEnabled(): Boolean = getExtension().isEnabled

    private fun Project.logModifierChanges(): Boolean = getExtension().logModifierChanges
    private fun Project.logFunctionChanges(): Boolean = getExtension().logFunctionChanges

    private fun Project.getExtension(): RecomposeLoggerExtension {
        return project.extensions.findByType(RecomposeLoggerExtension::class.java)
            ?: project.extensions.create(EXTENSION_NAME, RecomposeLoggerExtension::class.java)
    }

    companion object {
        private const val EXTENSION_NAME = "recomposeLogger"
    }

}